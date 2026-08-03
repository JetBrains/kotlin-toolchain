/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes.output

import org.jetbrains.amper.processes.ProcessResult

/**
 * Captures the stdout/stderr streams so they can be returned as part of an extended [ProcessResult].
 */
internal abstract class InMemoryCapture<R : ProcessResult>(
    otherListener: ProcessOutputListener,
    captureStdout: Boolean,
    override val redirectStderrToStdout: Boolean,
) : ProcessOutputMode.Listen<R> {

    private val capturingListener = CapturingListener(captureStdout)

    override val listener: ProcessOutputListener = capturingListener + otherListener

    override fun refineResult(baseResult: ProcessResult): R = refineResult(
        baseResult = baseResult,
        stdout = capturingListener.stdoutBuffer.toString(),
        stderr = capturingListener.stderrBuffer.toString(),
    )

    abstract fun refineResult(baseResult: ProcessResult, stdout: String, stderr: String): R
}

/**
 * Captures the stdout/stderr streams so they can be returned as part of a [ProcessResult.WithOutputs].
 */
internal class InMemoryCaptureIndependentStreams(
    otherListener: ProcessOutputListener,
) : InMemoryCapture<ProcessResult.WithOutputs>(
    otherListener = otherListener,
    captureStdout = true,
    redirectStderrToStdout = false
) {

    override fun refineResult(baseResult: ProcessResult, stdout: String, stderr: String): ProcessResult.WithOutputs =
        ProcessResultWithCapturedOutputs(
            command = baseResult.command,
            exitCode = baseResult.exitCode,
            pid = baseResult.pid,
            stdout = stdout,
            stderr = stderr,
        )

    private data class ProcessResultWithCapturedOutputs(
        override val command: List<String>,
        override val pid: Long,
        override val exitCode: Int,
        override val stdout: String,
        override val stderr: String,
    ) : ProcessResult.WithOutputs
}

/**
 * Captures the stdout/stderr streams so they can be returned as part of a [ProcessResult.WithOutputs].
 */
internal class InMemoryCaptureStderrOnly(
    otherListener: ProcessOutputListener,
) : InMemoryCapture<ProcessResult.WithStderr>(
    otherListener = otherListener,
    captureStdout = false,
    redirectStderrToStdout = false,
) {

    override fun refineResult(baseResult: ProcessResult, stdout: String, stderr: String): ProcessResult.WithStderr =
        ProcessResultWithCapturedStderr(
            command = baseResult.command,
            exitCode = baseResult.exitCode,
            pid = baseResult.pid,
            stderr = stderr,
        )

    private data class ProcessResultWithCapturedStderr(
        override val command: List<String>,
        override val pid: Long,
        override val exitCode: Int,
        override val stderr: String,
    ) : ProcessResult.WithStderr
}

/**
 * Captures the stdout/stderr streams interleaved together as one stream, and return them as part of a
 * [ProcessResult.WithMergedOutputs].
 */
internal class InMemoryCaptureMergedStreams(
    otherListener: ProcessOutputListener,
) : InMemoryCapture<ProcessResult.WithMergedOutputs>(
    otherListener = otherListener,
    captureStdout = true,
    redirectStderrToStdout = true,
) {

    override fun refineResult(
        baseResult: ProcessResult,
        stdout: String,
        stderr: String,
    ): ProcessResult.WithMergedOutputs =
        ProcessResultWithCapturedMergedOutputs(
            command = baseResult.command,
            exitCode = baseResult.exitCode,
            pid = baseResult.pid,
            stdoutAndStderr = stdout, // contains the redirected stderr
        )

    private data class ProcessResultWithCapturedMergedOutputs(
        override val command: List<String>,
        override val pid: Long,
        override val exitCode: Int,
        override val stdoutAndStderr: String,
    ) : ProcessResult.WithMergedOutputs
}

/**
 * A [ProcessOutputListener] that captures the outputs in memory and allows to access them after the process exits.
 */
private class CapturingListener(private val captureStdout: Boolean) : ProcessOutputListener {
    val stdoutBuffer: StringBuilder = StringBuilder()
    val stderrBuffer: StringBuilder = StringBuilder()

    override fun onStdoutLine(line: String, pid: Long) {
        if (captureStdout) {
            stdoutBuffer.appendLine(line)
        }
    }

    override fun onStderrLine(line: String, pid: Long) {
        stderrBuffer.appendLine(line)
    }
}
