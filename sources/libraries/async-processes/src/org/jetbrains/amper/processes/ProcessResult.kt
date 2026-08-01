/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

/**
 * The result of a completed process.
 */
interface ProcessResult {
    /**
     * The command line that was executed.
     */
    val command: List<String>
    /**
     * The ID identifying this process when it was alive.
     */
    val pid: Long
    /**
     * The exit code of the process.
     */
    val exitCode: Int
    /**
     * Whether the error stream was redirected to the standard output of the process.
     */
    val errorStreamRedirected: Boolean

    /**
     * The result of a completed process, with captured output.
     */
    interface WithOutputs : ProcessResult {
        /**
         * If [errorStreamRedirected] is false, [stdout] contains the whole standard output of the process, decoded as
         * UTF-8 text.
         * If [errorStreamRedirected] is true, [stdout] contains both the merged stdout and stderr of the process,
         * interlaced as they were written by the process.
         */
        val stdout: String
        /**
         * The whole standard error stream of the process, decoded as UTF-8 text, or the empty string if
         * [errorStreamRedirected] is true (in that case, the stderr content is in [stdout], interlaced with the standard
         * output).
         */
        val stderr: String
    }
}

internal data class SimpleProcessResult(
    override val command: List<String>,
    override val pid: Long,
    override val exitCode: Int,
    override val errorStreamRedirected: Boolean,
) : ProcessResult

internal data class ProcessResultWithCapturedOutputs(
    override val command: List<String>,
    override val pid: Long,
    override val exitCode: Int,
    override val errorStreamRedirected: Boolean,
    override val stdout: String,
    override val stderr: String,
) : ProcessResult.WithOutputs
