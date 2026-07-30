/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.serializableKotlinSettings
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.kotlin.native.downloadAndExtractKotlinNative
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import java.io.PrintStream
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

internal class DumpKlibSignaturesTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val userCacheRoot: AmperUserCacheRoot,
    private val terminal: Terminal,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val kotlinUserSettings = module.fragments.first().serializableKotlinSettings()
        val konanDistribution = Downloader.downloadAndExtractKotlinNative(kotlinUserSettings.compilerVersion, userCacheRoot)
            ?: error("kotlin native compiler is not available for the current platform")
        val klibBinary = konanDistribution.homeDir / "bin" / "klib"
        val klibPath = System.getenv("INPUT_KLIB")
        val outputDump = Path(System.getenv("OUTPUT_DUMP"))
        PrintStream(outputDump.outputStream()).use { outputFile ->
            outputFile.writer().use { writer ->
                val result = runProcess(
                    command = listOf(klibBinary.pathString, "dump-metadata-signatures", klibPath),
                    environment = mapOf(
                        // TC machine doesn't have any Java runtimes in /Library/Java, so fallback to java.home
                        "JAVA_HOME" to (System.getenv()["JAVA_HOME"]?.let { it.ifEmpty { null } } ?: System.getProperty("java.home")),
                    ),
                    outputMode = ProcessOutputMode.listen(
                        object : ProcessOutputListener {
                            override fun onStdoutLine(line: String, pid: Long) {
                                writer.appendLine(line)
                            }

                            override fun onStderrLine(line: String, pid: Long) {
                                terminal.println(line)
                            }
                        },
                    ),
                )
                if (result.exitCode != 0) {
                    userReadableError("Failed to dump klib signatures")
                }
            }
        }
        return EmptyTaskResult
    }
}