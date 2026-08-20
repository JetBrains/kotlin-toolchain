/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.cli.widgets.PlatformProgressReporter.Progress
import kotlin.math.roundToInt

/**
 * A reporter that notifies the terminal/os about the progress of an operation via the special escape sequence.
 * Ensures that the progress is reset when JVM terminates.
 *
 * See [here](https://rockorager.dev/misc/osc-9-4-progress-bars/) for more info.
 */
interface PlatformProgressReporter {
    /**
     * Updates the progress to the specified [state].
     * @param state The new progress state
     */
    fun update(state: Progress)

    /**
     * Conventional states understood by the terminal/os.
     * In the end, the terminal/os will decide how to represent each state.
     */
    sealed interface Progress {
        /**
         * Percentage is reset and hidden (default state)
         */
        data object Hidden : Progress

        /**
         * Indeterminate/pulsing state
         */
        data object Indeterminate : Progress

        /**
         * Normal progress state with [percent] completion.
         */
        data class Percentage(
            val percent: Int,
            val state: State = State.Normal,
        ): Progress {
            enum class State {
                /**
                 * Operation is going normally.
                 */
                Normal,

                /**
                 * The progress is paused
                 */
                Paused,

                /**
                 * An error has occurred (typically red bar rendition)
                 */
                Error,
            }

            /**
             * Creates a progress state from a ratio between 0.0 and 1.0.
             * Values outside the range are clamped.
             */
            constructor(ratio: Float) : this((ratio * 100).roundToInt().coerceIn(0..100))

            init {
                require(percent in 0..100) { "Percentage percent must be between 0 and 100, got $percent" }
            }
        }
    }
}

/**
 * Creates a [PlatformProgressReporter] implementation based on the [terminal]'s info/capabilites.
 */
fun PlatformProgressReporter(
    terminal: Terminal,
) : PlatformProgressReporter = if (terminal.terminalInfo.outputInteractive) {
    PlatformProgressReporterImpl()
} else PlatformProgressReporterNoop

private class PlatformProgressReporterImpl(
    /* we don't use Terminal here for now. See TO-DO below */
) : PlatformProgressReporter {

    override fun update(state: Progress): Unit = synchronized(StateHolder) {
        if (isShutdown) return

        if (currentState != state) {
            doUpdate(state)
            currentState = state

            if (state is Progress.Hidden) {
                resetHook?.let(Runtime.getRuntime()::removeShutdownHook)
                resetHook = null
            } else {
                if (resetHook == null) {
                    resetHook = Thread {
                        synchronized(StateHolder) { isShutdown = true }
                        doUpdate(Progress.Hidden)
                    }.also(Runtime.getRuntime()::addShutdownHook)
                }
            }
        }
    }

    private fun doUpdate(state: Progress) {
        // We print directly bypassing `terminal.rawPrint` here,
        //  as using the later leads to unexpected newlines in the TUI.
        //  TODO: figure out why/report issue?
        @Suppress("ReplacePrintlnWithLogging")
        print("${OSC}9;4;${codeOf(state)}$ST")
        System.out.flush()  // Need to ensure this gets to the STDOUT without the `\n`.
    }

    private fun codeOf(state: Progress): String {
        return when (state) {
            Progress.Hidden -> "0"
            Progress.Indeterminate -> "3"
            is Progress.Percentage -> when (state.state) {
                Progress.Percentage.State.Normal -> "1;${state.percent}"
                Progress.Percentage.State.Error -> "2;${state.percent}"
                Progress.Percentage.State.Paused -> "4;${state.percent}"
            }
        }
    }

    private companion object StateHolder {
        private var currentState: Progress? = null
        private var isShutdown = false
        private var resetHook: Thread? = null
    }
}

private object PlatformProgressReporterNoop : PlatformProgressReporter {
    override fun update(state: Progress) = Unit
}

private const val ESC = "\u001B"
private const val OSC = "$ESC]"
private const val ST = "$ESC\\"
