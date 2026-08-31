/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.stdlib.runtime.runOnJvmShutdown

/**
 * Manages terminal cursor visibility safely.
 * Ensures that the cursor is shown when the JVM is shutdown.
 */
interface TerminalCursorManager {
    /**
     * Hide the cursor if it's not yet hidden.
     */
    fun ensureHidden()

    /**
     * Show the cursor if it's not yet shown.
     */
    fun ensureShown()
}

/**
 * Creates [TerminalCursorManager] instance.
 */
fun TerminalCursorManager(terminal: Terminal): TerminalCursorManager =
    if (terminal.terminalInfo.outputInteractive) {
        InteractiveTerminalCursorManager(terminal)
    } else TerminalCursorManagerNoop

private class InteractiveTerminalCursorManager(
    private val terminal: Terminal,
) : TerminalCursorManager {
    override fun ensureHidden(): Unit = synchronized(StateHolder) {
        if (isShutdown) return

        if (isHidden != true) {
            terminal.cursor.hide(showOnExit = false /* we manage it ourselves */)
            isHidden = true
        }
        if (!resetHookSet) {
            runOnJvmShutdown {
                synchronized(StateHolder) { isShutdown = true }
                terminal.cursor.show()
            }
            resetHookSet = true
        }
    }

    override fun ensureShown(): Unit = synchronized(StateHolder) {
        if (isShutdown) return

        if (isHidden != false) {
            terminal.cursor.show()
            isHidden = false
        }
    }

    private companion object StateHolder {
        private var isHidden: Boolean? = null
        private var isShutdown = false
        private var resetHookSet: Boolean = false
    }
}

private object TerminalCursorManagerNoop : TerminalCursorManager {
    override fun ensureHidden() = Unit
    override fun ensureShown() = Unit
}