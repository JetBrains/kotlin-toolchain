/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes.output

interface ProcessOutputListener {
    /**
     * Called after each [line] of output on the stdout stream of the process identified by the given [pid].
     *
     * The output is decoded as UTF-8.
     *
     * This callback is not re-entrant. It will never be called concurrently with itself. However, it will be called
     * concurrently with [onStderrLine].
     */
    fun onStdoutLine(line: String, pid: Long)

    /**
     * Called after each [line] of output on the stdout stream of the process identified by the given [pid].
     *
     * The output is decoded as UTF-8.
     *
     * This callback is not re-entrant. It will never be called concurrently with itself. However, it will be called
     * concurrently with [onStdoutLine].
     */
    fun onStderrLine(line: String, pid: Long)

    /**
     * Called immediately when the process terminates.
     *
     * There might still be some output to process, so [onStdoutLine] and [onStderrLine] might still be called after
     * this. Use [onStreamsFlushed] if you want to make sure there is no more output.
     */
    fun onProcessTerminated(exitCode: Int, pid: Long) {}

    /**
     * Called after the process has terminated and all the output has been processed.
     *
     * The [onStdoutLine] and [onStderrLine] callbacks are guaranteed not to be called again after this.
     */
    fun onStreamsFlushed(exitCode: Int, pid: Long) {}

    /**
     * A [ProcessOutputListener] that ignores all output.
     */
    object NOOP : ProcessOutputListener {
        override fun onStdoutLine(line: String, pid: Long) {}
        override fun onStderrLine(line: String, pid: Long) {}
    }

    /**
     * Combines this listener with [other] into a new listener that notifies both.
     */
    operator fun plus(other: ProcessOutputListener): ProcessOutputListener =
        if (this is CompositeProcessOutputListener) {
            CompositeProcessOutputListener(listeners + other)
        } else {
            CompositeProcessOutputListener(listOf(this, other))
        }
}

private class CompositeProcessOutputListener(val listeners: List<ProcessOutputListener>) : ProcessOutputListener {
    override fun onStdoutLine(line: String, pid: Long) {
        listeners.forEach { it.onStdoutLine(line, pid) }
    }

    override fun onStderrLine(line: String, pid: Long) {
        listeners.forEach { it.onStderrLine(line, pid) }
    }

    override fun onProcessTerminated(exitCode: Int, pid: Long) {
        listeners.forEach { it.onProcessTerminated(exitCode, pid) }
    }

    override fun onStreamsFlushed(exitCode: Int, pid: Long) {
        listeners.forEach { it.onStreamsFlushed(exitCode, pid) }
    }
}
