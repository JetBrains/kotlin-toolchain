/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Starts a new process based on this [ProcessBuilder], and awaits its completion.
 * While waiting, stdout and stderr are sent to the given [outputListener], but are also fully captured in memory, so
 * they can be returned as a [ProcessResult]. Make sure the process doesn't output too much data, otherwise prefer
 * [run][ProcessBuilder.run].
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
 * @return a [ProcessResult] encapsulating information about the process, including its entire stdout and stderr.
 */
internal suspend fun ProcessBuilder.runAndCaptureOutput(
    input: ProcessInput = ProcessInput.Empty,
    outputListener: ProcessOutputListener = ProcessOutputListener.NOOP,
    onStart: (pid: Long) -> Unit = {},
): ProcessResult.WithOutputs {
    contract {
        callsInPlace(onStart, InvocationKind.EXACTLY_ONCE)
    }
    val capture = ProcessOutputListener.InMemoryCapture()
    val result = run(
        outputListener = outputListener + capture,
        input = input,
        onStart = onStart,
    )
    return ProcessResultWithCapturedOutputs(
        command = result.command,
        exitCode = result.exitCode,
        pid = result.pid,
        errorStreamRedirected = result.errorStreamRedirected,
        stdout = capture.stdout,
        stderr = capture.stderr,
    )
}

/**
 * Starts a new process based on this [ProcessBuilder], and awaits its completion.
 * While waiting, stdout and stderr are sent to the given [outputListener].
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
internal suspend fun ProcessBuilder.run(
    outputListener: ProcessOutputListener,
    input: ProcessInput = ProcessInput.Empty,
    onStart: (pid: Long) -> Unit = {},
): ProcessResult {
    contract {
        callsInPlace(onStart, InvocationKind.EXACTLY_ONCE)
    }
    return withContext(Dispatchers.IO) {
        redirectOutput(ProcessBuilder.Redirect.PIPE)
        redirectError(ProcessBuilder.Redirect.PIPE)
        redirectInput(input.stdinRedirection)
        start().withGuaranteedTermination { process ->
            onStart(process.pid())
            launch {
                // input writing is asynchronous
                input.writeTo(process.outputStream)
            }
            val exitCode = process.awaitListening(outputListener)
            SimpleProcessResult(
                command = command().toList(),
                exitCode = exitCode,
                pid = process.pid(),
                errorStreamRedirected = redirectErrorStream(),
            )
        }
    }
}

private val ProcessInput.stdinRedirection: ProcessBuilder.Redirect
    get() = when (this) {
        ProcessInput.Inherit -> ProcessBuilder.Redirect.INHERIT
        ProcessInput.Empty,
        is ProcessInput.Text,
        is ProcessInput.Pipe -> ProcessBuilder.Redirect.PIPE
    }

/**
 * Starts a new process based on this [ProcessBuilder], and awaits its completion.
 *
 * While waiting, all standard streams of the child process are inherited from the current process.
 * This function is useful when starting interactive processes that may require user input, or when running processes
 * for which the output should just be printed normally to the console like the rest of the code.
 *
 * > Note: this function replaces any previous stream configuration made via
 * [redirectOutput][ProcessBuilder.redirectOutput], [redirectInput][ProcessBuilder.redirectInput],
 * [redirectError][ProcessBuilder.redirectError], but it respects
 * [redirectErrorStream][ProcessBuilder.redirectErrorStream].
 *
 * If the current coroutine is canceled before the process has terminated, the process is killed (first normally, then
 * forcibly). If the process is not killable and hangs, this function will also hang instead of returning (otherwise
 * the zombie process could leak).
 *
 * If the JVM is terminated gracefully (Ctrl+C / SIGINT), this function **requests the process destruction** but doesn't
 * wait for its completion (we mustn't block the JVM shutdown).
 *
 * @return the exit code of the process
 */
internal suspend fun ProcessBuilder.runWithInheritedIO(onStart: (pid: Long) -> Unit = {}): ProcessResult {
    contract {
        callsInPlace(onStart, InvocationKind.EXACTLY_ONCE)
    }
    return withContext(Dispatchers.IO) {
        inheritIO()
            .start()
            .withGuaranteedTermination { process ->
                onStart(process.pid())
                val exitCode = process.onExit().await().exitValue()
                SimpleProcessResult(
                    command = command().toList(),
                    exitCode = exitCode,
                    pid = process.pid(),
                    errorStreamRedirected = redirectErrorStream(),
                )
            }
    }
}
