/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes.output

interface ProcessOutputListener {
    fun onStdoutLine(line: String, pid: Long)
    fun onStderrLine(line: String, pid: Long)
    fun onProcessTerminated(exitCode: Int, pid: Long) {}

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
}
