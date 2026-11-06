/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */


import iosUtils.IOSBaseTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.amper.processes.ExitCode
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.test.processes.TestReporterProcessOutputListener
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests that `run --wait-for-debugger` starts an iOS app suspended on a simulator, so that a regular LLDB
 * can attach to it, and that the CLI keeps running until the debugged app exits.
 */
@Tag("ios-simulator")
class IOSSimulatorRunTest : IOSBaseTest() {

    @Test
    fun waitForDebuggerAllowsAttachingLldb() = runBlocking {
        val projectDir = copyProjectToTempDir(testProject("swiftonlytodo"))

        coroutineScope {
            val launchInfoPrinted = CompletableDeferred<Unit>()
            val cliOutputLines = CopyOnWriteArrayList<String>()
            val cli = async {
                runAmper(
                    workingDir = projectDir,
                    args = ["run", "-p", "iosSimulatorArm64", "--wait-for-debugger"],
                    assertEmptyStdErr = false,
                    outputListener = TestReporterProcessOutputListener("ktc", testReporter) +
                            object : ProcessOutputListener {
                                override fun onStdoutLine(line: String, pid: Long) {
                                    cliOutputLines += line
                                    if ("Launch info:" in line) launchInfoPrinted.complete(Unit)
                                }

                                override fun onStderrLine(line: String, pid: Long) {
                                    cliOutputLines += line
                                }

                                override fun onStreamsFlushed(exitCode: ExitCode, pid: Long) {
                                    launchInfoPrinted.completeExceptionally(AssertionError(
                                        "The CLI exited with code $exitCode before printing the launch info.\n" +
                                                "CLI output:\n${cliOutputLines.joinToString("\n")}"
                                    ))
                                }
                            },
                )
            }

            withTimeout(20.minutes) { launchInfoPrinted.await() }
            val launchInfo = parseLaunchInfo(cliOutputLines.joinToString("\n"))

            try {
                // The app is launched suspended, so that a regular LLDB can attach to it
                awaitAppState(launchInfo.pid, expectSuspended = true)
                attachLldbAndDetach(launchInfo.pid)

                // After the debugging session ends, the app keeps running
                awaitAppState(launchInfo.pid, expectSuspended = false)

                // The CLI keeps the debugging session alive until the app exits
                runProcess(
                    command = ["xcrun", "simctl", "terminate", launchInfo.deviceId, launchInfo.bundleId],
                    outputMode = ProcessOutputMode.Discard,
                )
                val result = withTimeout(1.minutes) { cli.await() }

                assertTrue(
                    "Starting the app, it will wait for a debugger to attach" in result.stdoutClean,
                    "The CLI did not report that the app waits for a debugger.\nCLI output:\n${result.stdoutClean}",
                )
                assertTrue(
                    "The app has exited" in result.stdoutClean,
                    "The CLI did not report the app exit.\nCLI output:\n${result.stdoutClean}",
                )
            } finally {
                // Don't leave a suspended app behind if the test fails in the middle
                runProcess(
                    command = ["xcrun", "simctl", "terminate", launchInfo.deviceId, launchInfo.bundleId],
                    outputMode = ProcessOutputMode.Discard,
                )
            }
        }
    }

    /**
     * Attaches a regular LLDB to the app process with the given [pid] in batch mode and detaches from it,
     * which also resumes the process.
     */
    private suspend fun attachLldbAndDetach(pid: Long) {
        val lldbResult = runProcess(
            command = ["xcrun", "lldb", "--batch", "--attach-pid", pid.toString(), "-o", "process detach"],
            outputMode = ProcessOutputMode.captureMergedStreams(),
        )
        assertEquals(
            0,
            lldbResult.exitCode.value,
            "Attaching LLDB to the suspended app failed.\nLLDB output:\n${lldbResult.stdoutAndStderr}"
        )
    }

    /**
     * Waits until the app process with the given [pid] is either suspended or running and alive.
     *
     * Fails if the expected state is not reached within the timeout.
     */
    private suspend fun awaitAppState(pid: Long, expectSuspended: Boolean) {
        withTimeout(30.seconds) {
            while (!appProcessInState(pid, expectSuspended)) {
                delay(200.milliseconds)
            }
        }
    }

    private suspend fun appProcessInState(pid: Long, suspended: Boolean): Boolean {
        val state = appProcessState(pid)
        return if (suspended) {
            state.startsWith("T")
        } else {
            // An empty state means the process no longer exists
            state.isNotEmpty() && !state.startsWith("T")
        }
    }

    private suspend fun appProcessState(pid: Long): String = runProcess(
        command = ["ps", "-o", "state=", "-p", pid.toString()],
        outputMode = ProcessOutputMode.capture(),
    ).stdout.trim()

    private fun parseLaunchInfo(cliOutput: String): IosAppLaunchInfo {
        // The line may be wrapped by the terminal, so newlines are normalized before parsing
        val line = cliOutput.substringAfterLast("Launch info:").replace('\n', ' ')
        val match = LAUNCH_INFO_REGEX.find(line)
            ?: error("Failed to parse the launch info line: $line")
        val deviceId = match.groupValues[1]
        val bundleId = match.groupValues[2]
        val pid = match.groupValues[3]
        val suspended = match.groupValues[4]
        check(suspended == "true") { "Expected the app to be launched suspended, but got: $line" }
        val pidValue = pid.toLongOrNull() ?: error("The CLI failed to report the app PID: $line")
        return IosAppLaunchInfo(deviceId = deviceId, bundleId = bundleId, pid = pidValue)
    }
}

private data class IosAppLaunchInfo(
    val deviceId: String,
    val bundleId: String,
    val pid: Long,
)

private val LAUNCH_INFO_REGEX = Regex("""deviceId=(\S+) bundleId=(\S+) pid=(\S+) suspended=(\S+)""")
