/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

private val isWindows = System.getProperty("os.name").startsWith("win", ignoreCase = true)

class ProcessesTest {
    private val unknownCommandExitCode = if (isWindows) 1 else 127
    private val cancelledExitCode = if (isWindows) 1 else 137
    private val loremIpsum1000 =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Phasellus nibh odio, auctor non tincidunt eu, posuere vitae nisl. Sed lobortis gravida sapien, eget feugiat purus feugiat et. Fusce ullamcorper risus ac diam varius, ullamcorper molestie est aliquam. Ut dictum, tellus sit amet efficitur hendrerit, est dolor bibendum nunc, et lacinia sem erat nec lectus. Donec orci elit, feugiat in arcu vel, dictum ultricies diam. Nullam ut ultricies tortor. Sed a finibus tortor. Vestibulum et diam vitae orci hendrerit faucibus ac posuere leo. Nunc laoreet interdum euismod. Pellentesque ac porttitor enim. In malesuada pharetra orci in euismod. Quisque sit amet rutrum enim. Morbi ultrices blandit augue, non tincidunt sapien sagittis sit amet. Mauris id tempus tortor, vitae ullamcorper orci. Phasellus efficitur dolor mollis, mattis lacus quis, convallis elit. Phasellus dignissim, nibh a aliquam commodo, ipsum risus suscipit massa, et porta lacus eros nec felis. Nulla ante augue, elementum cras amet."

    @Test
    fun `runProcessAndCaptureOutput should capture stdout and stderr`() = runBlocking(Dispatchers.IO) {
        val command = shell(
            shCommand = "printf 'line1\n'; printf 'line2\nbreak'; printf 'hello stderr' 1>&2",
            psCommand = "Write-Output 'line1'; Write-Output 'line2'; Write-Output 'break'; [Console]::Error.Write('hello stderr')",
        )
        val result = runProcessAndCaptureOutput(command = command)
        assertZeroExitCode(result)
        assertEquals(listOf("line1", "line2", "break"), result.stdout.trim().lines())
        assertEquals("hello stderr", result.stderr.trim())
    }

    @Test
    fun `runProcessAndCaptureOutput should capture stderr in case of wrong nested command`() = runBlocking(Dispatchers.IO) {
        val command = shell(
            shCommand = "echo line1; not-a-command",
            psCommand = "Write-Output 'line1'; not-a-command",
        )
        val result = runProcessAndCaptureOutput(command = command)
        assertEquals(unknownCommandExitCode, result.exitCode)
        assertEquals("line1", result.stdout.trim())
        assertContains(result.stderr, "not-a-command")

        val expectedError = when {
            isWindows -> "not recognized"
            else -> "not found"
        }
        assertContains(result.stderr, expectedError)
    }

    // We don't want to crash if the process is killed externally, we want to read its exit code and stdout/stderr,
    // and possibly report errors as we want.
    @Test
    fun `awaitListening should terminate normally if the process is killed externally`() = runBlocking(Dispatchers.IO) {
        @Suppress("PROCESS_BUILDER_START_LEAK") // we're literally testing the mechanism
        val process = ProcessBuilder(echoLoop(n = 10_000_000, message = loremIpsum1000)).start()

        val firstOutputEvent = CompletableDeferred<Unit>()
        val capture = ProcessOutputListener.InMemoryCapture()

        val deferredExitCode = async {
            process.awaitListening(
                outputListener = capture + object : ProcessOutputListener {
                    override fun onStdoutLine(line: String, pid: Long) {
                        firstOutputEvent.complete(Unit)
                    }

                    override fun onStderrLine(line: String, pid: Long) {
                        firstOutputEvent.complete(Unit)
                    }
                }
            )
        }

        // make sure we got some output from the process, confirming it is running
        firstOutputEvent.await()

        // simulate external kill via regular API
        process.destroyForcibly()
        process.waitFor(1, TimeUnit.SECONDS)
        assertTerminated(process, "The process should have terminated by now, because it was explicitly killed")

        val (exitCode = value, duration) = measureTimedValue {
            withTimeoutOrNull(5.seconds) { deferredExitCode.await() }
        }
        assertTrue(duration < 1.seconds, "The result should be returned quickly (<1s) after the destruction of the process, but it took $duration")
        check(exitCode != null) {
            "The exitCode should not be null (which means timeout) it the assertion about the duration succeeded"
        }

        // We don't assert anything on stderr, because the way the process is killed may lead to unpredictable stderr.
        // For example, on Windows, there seems to be races between the cleanup of the standard streams pipes and the
        // death of the process, leading to errors like: "The process tried to write to a nonexistent pipe".
        assertTrue(capture.stdout.startsWith(loremIpsum1000), "At least the first line of output should have been captured, but got: ${capture.stdout}")
        assertEquals(cancelledExitCode, exitCode, "The exit code should be the cancellation exit code $cancelledExitCode")
    }

    @Test
    fun `should transfer custom env`() = runBlocking(Dispatchers.IO) {
        val result = runProcessAndCaptureOutput(
            command = echoEnv("MY_ENV"),
            environment = mapOf("MY_ENV" to "env_value"),
        )
        assertZeroExitCode(result)
        assertEquals("env_value", result.stdout.trim())
        assertEquals("", result.stderr)
    }

    @Test
    fun `withGuaranteedTermination should kill the process when canceled`() = runBlocking(Dispatchers.IO) {
        @Suppress("PROCESS_BUILDER_START_LEAK") // we're literally testing the mechanism
        val process = ProcessBuilder(sleep(seconds = 600)).start()
        val userBlockStartEvent = CompletableDeferred<Unit>()
        val job = launch {
            process.withGuaranteedTermination {
                userBlockStartEvent.complete(Unit)
                delay(30.seconds)
            }
        }
        userBlockStartEvent.await()
        val time = measureTime {
            job.cancelAndJoin()
        }
        assertTerminated(process, "withGuaranteedTermination should not exit before the process is terminated")
        assertTrue(time < 5.seconds, "The process should be terminated almost instantly, but ran for $time")
    }

    @Test
    fun `withGuaranteedTermination should kill the process when canceled even when reading streams`() = runBlocking(Dispatchers.IO) {
        @Suppress("PROCESS_BUILDER_START_LEAK") // we're literally testing the mechanism
        val process = ProcessBuilder(
            shell(
                shCommand = "echo 'started'; sleep 600",
                psCommand = "Write-Output 'started'; Start-Sleep -Seconds 600",
            )
        ).start()
        val firstOutputLineEvent = CompletableDeferred<Unit>()
        val job = launch {
            process.withGuaranteedTermination {
                process.awaitListening(object : ProcessOutputListener {
                    override fun onStdoutLine(line: String, pid: Long) {
                        assertEquals("started", line)
                        firstOutputLineEvent.complete(Unit)
                    }
                    override fun onStderrLine(line: String, pid: Long) {
                    }
                })
            }
        }
        firstOutputLineEvent.await()
        val time = measureTime {
            job.cancelAndJoin()
        }
        assertTerminated(process, "withGuaranteedTermination should not exit before the process is terminated")
        assertTrue(time < 5.seconds, "The process should be terminated almost instantly, but ran for $time")
    }

    @Test
    fun `withGuaranteedTermination should kill the process when already cancelled`() = runBlocking(Dispatchers.IO) {
        @Suppress("PROCESS_BUILDER_START_LEAK") // we're literally testing the mechanism
        val process = ProcessBuilder(sleep(seconds = 600)).start()

        val launchJob = launch {
            cancel() // simulates that the coroutine is already canceled before the call
            process.withGuaranteedTermination {
                fail("The body of withGuaranteedTermination should not be run if the coroutine is already cancelled")
            }
        }
        val time = measureTime {
            launchJob.join()
        }
        assertTerminated(process, "withGuaranteedTermination should not exit before the process is terminated")
        assertTrue(time < 1.seconds, "The process should be terminated almost instantly, but ran for $time")
    }
}

private fun assertTerminated(process: Process, message: String) {
    if (!process.isAlive) {
        return
    }
    process.destroyForcibly()
    val testProcessTerminated = process.waitFor(1, TimeUnit.SECONDS)
    if (!testProcessTerminated) {
        System.err.println("Failed to kill test process ${process.pid()} (still not terminated after 1s)")
    }
    fail("$message. Killed afterwards: $testProcessTerminated")
}

private fun sleep(seconds: Int): List<String> = shell(
    shCommand = "sleep $seconds",
    psCommand = "Start-Sleep -Seconds $seconds",
)

private fun echoEnv(envVarName: String) = shell(
    shCommand = "echo \$$envVarName",
    psCommand = "Write-Output \$env:$envVarName",
)

private fun echoLoop(n: Int, message: String) = shell(
    shCommand = "for i in `seq 1 $n`; do echo '$message'; done",
    psCommand = $$"for ($i = 1; $i -le $$n; $i++) { Write-Output $${message.toPowerShellStringLiteral()} }",
)

private fun shell(shCommand: String, psCommand: String): List<String> = when {
    isWindows -> powershell(psCommand)
    else -> binSh(shCommand)
}

private fun binSh(command: String): List<String> = listOf("/bin/sh", "-c", command)

private fun powershell(command: String) = listOf("powershell.exe", "-NonInteractive", "-NoProfile", "-NoLogo", "-Command", command)

private fun String.toPowerShellStringLiteral(): String = "'${replace("'", "''")}'"

private fun assertZeroExitCode(result: ProcessResult) {
    assertEquals(0, result.exitCode,
        "Process terminated with non-zero exit code ${result.exitCode}. Output:\n" +
                "${result.stdout.prependIndent("stdout>")}\n" +
                "${result.stderr.prependIndent("stderr>")}\n"
    )
}
