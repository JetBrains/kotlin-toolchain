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
     * The result of a completed process, with the standard error stream captured as [stderr].
     *
     * @see WithOutputs
     */
    interface WithStderr : ProcessResult {
        /**
         * The whole standard error stream of the process, decoded as UTF-8 text.
         */
        val stderr: String
    }

    /**
     * The result of a completed process, with both standard streams captured as [stdout] and [stderr].
     */
    interface WithOutputs : WithStderr {
        /**
         * The whole standard output of the process, decoded as UTF-8 text.
         */
        val stdout: String
    }

    /**
     * The result of a completed process, with standard output and error streams merged together and captured as
     * [stdoutAndStderr].
     */
    interface WithMergedOutputs : ProcessResult {
        /**
         * The merged stdout and stderr of the process, interlaced as they were written by the process.
         */
        val stdoutAndStderr: String
    }
}

internal data class SimpleProcessResult(
    override val command: List<String>,
    override val pid: Long,
    override val exitCode: Int,
) : ProcessResult
