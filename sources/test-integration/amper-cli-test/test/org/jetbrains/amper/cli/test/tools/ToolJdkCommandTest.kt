/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.system.info.OsFamily
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.pathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@Tag("cli-test-group-tools")
class ToolJdkCommandTest : CliTestBase() {

    @Test
    fun `tool jdk jps`() = runSlowTest {
        val p = newEmptyProjectDir(setupWrappers = true)

        withIdleJvm { pid ->
            val result = runCli(p, "tool", "jdk", "jps")
            result.assertStdoutContains("$pid SourceLauncher") // we launch the process from a .java source
        }
    }

    @Test
    fun `tool jdk jstack`() = runSlowTest {
        val p = newEmptyProjectDir(setupWrappers = true)

        // We deliberately dump a separate idle JVM instead of the current one (see withIdleJvm).
        val result = withIdleJvm { pid ->
            runCli(p, "tool", "jdk", "jstack", pid.toString())
        }

        val requiredSubstring = "Full thread dump"
        assertTrue("stdout should contain '$requiredSubstring':\n${result.stdout}") {
            result.stdout.contains(requiredSubstring)
        }
    }

    /**
     * Starts a JVM that does nothing until it is destroyed, and runs [block] with its PID once it is attachable.
     */
    private suspend fun <R> withIdleJvm(block: suspend (pid: Long) -> R): R {
        val idleJvmDir = (tempRoot / "idle-jvm").createDirectories()
        val sourceFile = idleJvmDir / "IdleJvm.java"
        sourceFile.writeText(
            """
            public class IdleJvm {
                public static void main(String[] args) throws Exception {
                    System.out.println("started");
                    Thread.sleep(Long.MAX_VALUE);
                }
            }
            """.trimIndent()
        )

        return withContext(Dispatchers.IO) {
            val pid = CompletableDeferred<Long>()
            val appStarted = CompletableDeferred<Unit>()
            val idleJvmProcessJob = launch {
                runProcess(
                    workingDir = idleJvmDir,
                    command = [currentJavaExecutable().pathString, sourceFile.pathString],
                    outputMode = ProcessOutputMode.listen(object : ProcessOutputListener {
                        override fun onStdoutLine(line: String, pid: Long) {
                            if (line == "started") {
                                appStarted.complete(Unit)
                            }
                            println("[test jvm $pid out] $line")
                        }

                        override fun onStderrLine(line: String, pid: Long) {
                            println("[test jvm $pid err] $line")
                        }
                    }),
                    onStart = { pid.complete(it) }
                )
            }
            withTimeout(1.minutes) {
                appStarted.await()
            }
            try {
                block(pid.await())
            } finally {
                idleJvmProcessJob.cancel()
            }
        }
    }

    private fun currentJavaExecutable(): Path {
        val executableName = if (OsFamily.current.isWindows) "java.exe" else "java"
        return Path(System.getProperty("java.home")) / "bin" / executableName
    }
}
