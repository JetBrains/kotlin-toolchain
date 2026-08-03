/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes.output

import org.jetbrains.amper.processes.ProcessResult

/**
 * Defines how the output of the process should be handled, and can refine the type of the [ProcessResult] returned by
 * the functions executing the process (to optionally add more information).
 */
sealed interface ProcessOutputMode<out R : ProcessResult> {

    /**
     * Called once the process has terminated.
     *
     * This function can be used to return an extended [ProcessResult] with a more specific type [R].
     * If this is not needed, it can also simply return [baseResult] unchanged.
     */
    fun refineResult(baseResult: ProcessResult): R

    /**
     * Discards all output.
     */
    data object Discard : ProcessOutputMode<ProcessResult> {
        override fun refineResult(baseResult: ProcessResult): ProcessResult = baseResult
    }

    /**
     * Forwards all output to the parent process stdout/stderr, allowing interactivity and ANSI escapes.
     */
    data object Inherit : ProcessOutputMode<ProcessResult> {
        override fun refineResult(baseResult: ProcessResult): ProcessResult = baseResult
    }

    /**
     * Pipes the stdout/stderr of the child process to the current process so it can be sent to the [listener].
     * Note that this prevents the child process from using terminal interactions.
     */
    interface Listen<R : ProcessResult> : ProcessOutputMode<R> {

        /**
         * The listener that receives line-by-line output events from the standard streams of the child process.
         */
        val listener: ProcessOutputListener

        /**
         * Whether the standard error stream should be redirected to stdout, so both streams are merged together.
         * When this is `true`, all stderr output is sent to the stdout stream (and merged with the regular stdout),
         * so the [listener] only gets events in its stdout-related callbacks.
         */
        val redirectStderrToStdout: Boolean
    }

    companion object {
        /**
         * We can't provide a default transparent implementation of [refineResult] in the base [Listen] interface
         * because of the need to support a generic result. This class is here to provide this transparent default
         * [refineResult] thanks to the non-generic [ProcessResult] type.
         */
        private class ListenTransparent(
            override val listener: ProcessOutputListener,
            override val redirectStderrToStdout: Boolean,
        ) : Listen<ProcessResult> {
            override fun refineResult(baseResult: ProcessResult): ProcessResult = baseResult
        }

        /**
         * Listen to the output streams of the process via the given [listener].
         *
         * Use [redirectStderrToStdout] to merge both streams together and only listen to stdout events.
         */
        fun listen(
            listener: ProcessOutputListener,
            redirectStderrToStdout: Boolean = false,
        ): Listen<ProcessResult> = ListenTransparent(listener, redirectStderrToStdout)

        /**
         * Listen to the output streams of the process via the given [listener], and also capture the stdout and stderr
         * streams in memory to make them available in the result returned by the function executing the process.
         */
        fun listenAndCapture(listener: ProcessOutputListener): Listen<ProcessResult.WithOutputs> =
            InMemoryCaptureIndependentStreams(otherListener = listener)

        /**
         * Listen to the output streams of the process via the given [listener], and also capture the stderr stream
         * in memory to make it available in the result returned by the function executing the process. This is useful
         * to process or print errors after the process has terminated.
         */
        fun listenAndCaptureStderr(listener: ProcessOutputListener): Listen<ProcessResult.WithStderr> =
            InMemoryCaptureStderrOnly(otherListener = listener)

        /**
         * Capture the stdout and stderr streams in memory to make them available in the result returned by the
         * function executing the process.
         */
        fun capture(): Listen<ProcessResult.WithOutputs> =
            InMemoryCaptureIndependentStreams(otherListener = ProcessOutputListener.NOOP)

        /**
         * Discard the standard output of the process, but capture the stderr stream in memory to make it available in
         * the result returned by the function executing the process.
         */
        fun captureStderr(): Listen<ProcessResult.WithStderr> =
            InMemoryCaptureStderrOnly(otherListener = ProcessOutputListener.NOOP)

        /**
         * Capture the stdout and stderr streams in memory as a single unified text, which is available in the result
         * returned by the function executing the process.
         */
        fun captureMergedStreams(): Listen<ProcessResult.WithMergedOutputs> =
            InMemoryCaptureMergedStreams(otherListener = ProcessOutputListener.NOOP)
    }
}
