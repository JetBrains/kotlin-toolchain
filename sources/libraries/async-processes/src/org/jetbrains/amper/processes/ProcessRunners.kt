/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("PROCESS_BUILDER_START_LEAK")

package org.jetbrains.amper.processes

import org.jetbrains.amper.processes.output.ProcessOutputMode
import java.nio.file.Path
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Starts a new process with the given [command] in [workingDir], and awaits its completion.
 * While waiting, stdout and stderr are processed according to the given [outputMode] configuration.
 *
 * The given [input] is used to send data to the standard input of the started process.
 *
 * If the current coroutine is canceled before the process has terminated, the process is killed (first normally then
 * forcibly), and the stream readers cleaned up. If the process is not killable and hangs, this function will also hang
 * instead of returning (otherwise the zombie process could leak).
 *
 * **Note:** since the blocking reads of standard streams are not cancellable, this function may have to wait for the
 * read of the current line to complete before returning (on cancellation). This is to ensure no coroutines are leaked.
 * This wait should be reasonably short anyway because the process is killed on cancellation, so no more output
 * should be written in that case, ending the stream and completing the line read.
 *
 * If the JVM is terminated gracefully (Ctrl+C / SIGINT), this function **requests the process destruction** but doesn't
 * wait for its completion (we mustn't block the JVM shutdown).
 */
suspend fun <R : ProcessResult> runProcess(
    workingDir: Path? = null,
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
    outputMode: ProcessOutputMode<R>,
    input: ProcessInput = ProcessInput.Inherit,
    onStart: (pid: Long) -> Unit = {},
): R {
    contract {
        callsInPlace(onStart, InvocationKind.EXACTLY_ONCE)
    }
    return process(workingDir, command, environment).run(outputMode, input, onStart)
}

@RequiresOptIn("Using this API causes the child process to leak and outlive the execution of the current JVM. " +
        "Make sure you understand the consequences before opting in. " +
        "Please consider limiting the life of the process to at most that of the current JVM by using coroutines.")
annotation class ProcessLeak

/**
 * Starts a new process with the given [command] in [workingDir], detached from the execution of the current JVM.
 *
 * **WARNING:** this new process will not be stopped or awaited by this function call. Only use this function if the
 * intention is to start a long-lived process that survives across executions of this program.
 * In any other case, please prefer other functions that handle coroutines and process lifecycle.
 *
 * @return the started process's PID. This function doesn't return the [Process] object intentionally, because there
 * should be another way to interact with a long-lived process (some kind of IPC).
 */
@ProcessLeak
fun startLongLivedProcess(
    workingDir: Path? = null,
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
    redirectErrorStream: Boolean = false,
): Long { // NOT the Process, intentionally, because there must be some other way to interact with long-lived processes
    return process(
        workingDir = workingDir,
        command = command,
        environment = environment
    )
        .redirectErrorStream(redirectErrorStream)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        // no shutdown hook on purpose - we want to keep the process alive after the current JVM terminates
        .start()
        .apply {
            outputStream.close()
        }
        .pid()
}

private fun process(
    workingDir: Path? = null,
    command: List<String>,
    environment: Map<String, String> = emptyMap(),
): ProcessBuilder {
    require(command.isNotEmpty()) { "Cannot start a process with an empty command line" }

    return ProcessBuilder(command)
        .directory(workingDir?.toFile())
        .also { it.environment().putAll(environment) }
}
