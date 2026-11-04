/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets

import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.stdlib.runtime.runOnJvmShutdown
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import kotlin.system.exitProcess
import kotlin.time.Duration

/** Manages the terminal mode while a widget is displayed and restores it on JVM shutdown. */
internal interface TerminalModeManager {
    /**
     * Ensures the terminal is in the raw mode (if not supported - does nothing)
     */
    fun ensureEnteredRaw()

    /**
     * Ensures the terminal is NOT in the raw mode (if not supported - does nothing)
     */
    fun ensureExitedRaw()

    /**
     * When in raw mode, user input including system key combinations like `Ctrl+C` need to be handled manually.
     *
     * Calling this in a loop ensures that input is properly handled if in raw mode.
     * If not in raw mode - does nothing.
     */
    fun handleInputIfRawMode()
}

/**
 * Creates [TerminalModeManager] implementation based on the given [terminal].
 */
internal fun TerminalModeManager(terminal: Terminal): TerminalModeManager =
    if (terminal.terminalInfo.interactive) {
        InteractiveTerminalModeManager(terminal)
    } else TerminalModeManagerNoop

private class InteractiveTerminalModeManager(private val terminal: Terminal) : TerminalModeManager {
    private var scope: RawModeScope? = null

    override fun ensureEnteredRaw(): Unit = synchronized(StateHolder) {
        if (isShutdown) return
        if (scope == null) {
            check(activeMode == null) { "Terminal raw mode is already active" }
            ensureResetHook()
            scope = terminal.enterRawModeOrNull()
            if (scope != null) activeMode = this
        }
        if (cursorOwner == null) {
            ensureResetHook()
            cursorOwner = this
            terminal.cursor.hide(showOnExit = false /* we manage it ourselves */)
        }
    }

    override fun ensureExitedRaw(): Unit = synchronized(StateHolder) {
        try {
            exitRawMode()
        } finally {
            if (!isShutdown && cursorOwner === this) {
                terminal.cursor.show()
                cursorOwner = null
            }
        }
    }

    private fun exitRawMode() {
        val current = scope ?: return
        scope = null
        activeMode = null
        current.close()
    }

    override fun handleInputIfRawMode() = synchronized(StateHolder) {
        if (scope?.readKeyOrNull(Duration.ZERO)?.isCtrlC == true) {
            if (SystemInfo.CurrentHost.family == OsFamily.Windows) {
                // Windows: STATUS_CONTROL_C_EXIT
                exitProcess(0xC000013A.toInt())
            } else {
                // Linux & macOS: 128 + SIGINT
                exitProcess(130)
            }
        }
    }

    private companion object StateHolder {
        private var activeMode: InteractiveTerminalModeManager? = null
        private var cursorOwner: InteractiveTerminalModeManager? = null
        private var resetHookSet = false
        private var isShutdown = false

        private fun ensureResetHook() {
            if (resetHookSet) return
            runOnJvmShutdown {
                synchronized(StateHolder) {
                    isShutdown = true
                    val modeSetError = runCatching {
                        activeMode?.exitRawMode()
                    }.exceptionOrNull()

                    try {
                        cursorOwner?.terminal?.cursor?.show()
                    } catch (_: IllegalStateException) {
                        // If someone somewhere (maybe in Mordant's own code) called `cursor.hide(showOnExit = true)`,
                        // Mordant's own shutdown hook was registered. In this case, our call to `show()` here will try to
                        // remove this shutdown hook. But since we're already inside the execution of a shutdown hook, this
                        // will necessarily fail with 'IllegalStateException: Shutdown in progress'.
                        // We account for this here.
                    } catch (e: Throwable) {
                        modeSetError?.let(e::addSuppressed)
                        throw e
                    }

                    modeSetError?.let { throw it }
                }
            }
            resetHookSet = true
        }
    }
}

private object TerminalModeManagerNoop : TerminalModeManager {
    override fun ensureEnteredRaw() = Unit
    override fun ensureExitedRaw() = Unit
    override fun handleInputIfRawMode() = Unit
}
