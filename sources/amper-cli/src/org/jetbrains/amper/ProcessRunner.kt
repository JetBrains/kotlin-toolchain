/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper

import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.cli.telemetry.setProcessResultAttributes
import org.jetbrains.amper.intellij.CommandLineUtils
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.telemetry.ChildProcessTelemetry
import org.jetbrains.amper.telemetry.ChildProcessTelemetry.OTEL_FOLDER_ENV_VAR
import org.jetbrains.amper.telemetry.ChildProcessTelemetry.OTEL_PARENT_CONTEXT_ENV_VAR
import org.jetbrains.amper.util.ShellQuoting
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Ordinary operations like running processes and copying files require
 * comprehensive support in a build system to make it observable
 */
class ProcessRunner(
    /**
     * The directory in which to contribute telemetry traces from child processes.
     */
    private val telemetryDir: Path,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Starts a new process with the given [command] in [workingDir], and awaits the result.
     * While waiting, stdout and stderr are processed according to the given [outputMode].
     *
     * The given [input] is used to send data to the standard input of the started process.
     *
     * If a [span] is provided, extra attributes are added to it about the process result (exit code, stdout, stderr).
     *
     * If this function is canceled before the process has terminated, it kills the process (first normally, then
     * forcibly), and cleans the stream readers. If the process is not killable and hangs, this function will also hang
     * instead of returning (otherwise the zombie process could leak).
     *
     * **Note:** since the blocking reads of standard streams are not cancellable, this function may have to wait for
     * the read of the current line to complete before returning. This is to ensure no coroutines are leaked.
     * This wait should be reasonably short anyway because the process is killed on cancellation, so no more output
     * should be written in that case.
     */
    suspend fun <R : ProcessResult> runProcess(
        workingDir: Path,
        command: List<String>,
        span: Span? = null,
        environment: Map<String, String> = emptyMap(),
        outputMode: ProcessOutputMode<R>,
        input: ProcessInput = ProcessInput.Inherit,
    ): R {
        logger.debug("[cmd] ${ShellQuoting.quoteArgumentsPosixShellWay(command.toList())}")

        val result = withContext(Dispatchers.IO) {
            val environmentWithTelemetry = buildMap {
                put(OTEL_PARENT_CONTEXT_ENV_VAR, ChildProcessTelemetry.createSerializedParentContextData())
                put(OTEL_FOLDER_ENV_VAR, telemetryDir.pathString)
                putAll(environment)
            }

            org.jetbrains.amper.processes.runProcess(
                workingDir = workingDir,
                // Why quoteCommandLineForCurrentPlatform:
                // ProcessBuilder does not correctly escape its arguments on Windows
                // generally, JDK developers do not think that executed command should receive the same arguments as passed to ProcessBuilder
                // see, e.g., https://bugs.openjdk.org/browse/JDK-8131908
                // this code is mostly tested by AmperBackendTest.simple multiplatform cli on jvm
                command = CommandLineUtils.quoteCommandLineForCurrentPlatform(command),
                environment = environmentWithTelemetry,
                input = input,
                outputMode = outputMode,
            )
        }
        span?.setProcessResultAttributes(result)
        return result
    }
}
