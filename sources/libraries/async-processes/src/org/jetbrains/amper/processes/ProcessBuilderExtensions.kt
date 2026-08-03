/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.amper.processes.output.ProcessOutputMode
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Starts a new process based on this [ProcessBuilder], and awaits its completion.
 * While waiting, stdout and stderr are processed according to the given [outputMode] configuration.
 *
 * The given [input] is used to send data to the standard input of the started process (if non-empty).
 *
 * > Note: this function replaces any previous stream configuration made via
 * [redirectOutput][ProcessBuilder.redirectOutput], [redirectInput][ProcessBuilder.redirectInput],
 * [redirectError][ProcessBuilder.redirectError], but it respects
 * [redirectErrorStream][ProcessBuilder.redirectErrorStream].
 *
 * If the current coroutine is canceled before the process has terminated, the process is killed (first normally, then
 * forcibly), and the stream readers cleaned up. If the process is not killable and hangs, this function will also hang
 * instead of returning (otherwise the zombie process could leak).
 *
 * **Note:** since the blocking reads of standard streams are not cancellable, this function may have to wait for the
 * read of the current line to complete before returning (on cancellation). This is to ensure no coroutines are leaked.
 * This wait should be reasonably short anyway because the process is killed on cancellation, so no more output
 * should be written in that case.
 *
 * If the JVM is terminated gracefully (Ctrl+C / SIGINT), this function **requests the process destruction** but doesn't
 * wait for its completion (we mustn't block the JVM shutdown).
 *
 * @return the exit code of the process
 */
internal suspend fun <R : ProcessResult> ProcessBuilder.run(
    outputMode: ProcessOutputMode<R>,
    input: ProcessInput,
    onStart: (pid: Long) -> Unit = {},
): R {
    contract {
        callsInPlace(onStart, InvocationKind.EXACTLY_ONCE)
    }
    return withContext(Dispatchers.IO) {
        redirectOutput(outputMode.asProcessBuilderRedirect())
        redirectError(outputMode.asProcessBuilderRedirect())
        redirectInput(input.asProcessBuilderRedirect())
        if (outputMode is ProcessOutputMode.Listen) {
            redirectErrorStream(outputMode.redirectStderrToStdout)
        }
        start().withGuaranteedTermination { process ->
            onStart(process.pid())
            launch {
                // input writing is asynchronous
                input.writeTo(process.outputStream)
            }
            val exitCode = if (outputMode is ProcessOutputMode.Listen) {
                process.awaitListening(outputMode.listener)
            } else {
                process.onExit().await().exitValue()
            }
            outputMode.refineResult(
                SimpleProcessResult(
                    command = command().toList(),
                    exitCode = exitCode,
                    pid = process.pid(),
                )
            )
        }
    }
}

private fun ProcessOutputMode<*>.asProcessBuilderRedirect(): ProcessBuilder.Redirect = when (this) {
    ProcessOutputMode.Inherit -> ProcessBuilder.Redirect.INHERIT
    ProcessOutputMode.Discard -> ProcessBuilder.Redirect.DISCARD
    is ProcessOutputMode.Listen<*> -> ProcessBuilder.Redirect.PIPE
}

private fun ProcessInput.asProcessBuilderRedirect(): ProcessBuilder.Redirect = when (this) {
    ProcessInput.Inherit -> ProcessBuilder.Redirect.INHERIT
    ProcessInput.Empty,
    is ProcessInput.Text,
    is ProcessInput.Pipe -> ProcessBuilder.Redirect.PIPE
}
