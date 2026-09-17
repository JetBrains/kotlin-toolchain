/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.amper.cli.widgets.PlatformProgressReporter
import org.jetbrains.amper.cli.widgets.TerminalCursorManager
import org.jetbrains.amper.cli.widgets.status.tracking.GlobalTrackerSink
import org.jetbrains.amper.events.GlobalScopedEvent
import org.jetbrains.amper.events.sink.GlobalEventSink
import kotlin.time.TimeSource

/**
 * [GlobalEventSink] that consumes events and renders a status widget as an animation.
 *
 * @see OperationProgressWidgetSink
 */
class ProgressWidgetSink(
    private val terminal: Terminal,
    coroutineScope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : GlobalEventSink {
    private val delegate = StatusTrackerDelegateImpl(terminal, timeSource)
    private val stateTracker = GlobalTrackerSink(delegate = delegate)
    private val cursor = TerminalCursorManager(terminal)
    private val platformProgressReporter = PlatformProgressReporter(terminal)

    private val animation = terminal.animation<BuildState> { state ->
        state.render(terminal = terminal)
    }

    init {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                var lastUpdated = timeSource.markNow()
                while (true) {
                    if (
                        delegate.consumeUpdate() ||
                        (timeSource.markNow() - lastUpdated) >= WidgetTimings.WidgetMaxFrameUpdateInterval
                    ) {
                        // NOTE: We only display the latest build
                        val build = stateTracker.builds.maxByOrNull { it.startTime }
                        // Clear the widget if no build is active
                        // or any task requires interactive access to the terminal
                        if (build == null || build.taskStates.any { it.isInteractive }) {
                            // Quick-fix: before the `RunTask` implementors are properly refactored to not be tasks,
                            // we simply cancel the widget to not mess with the potentially interactive processes
                            // that are launched from the task.
                            hideAnimation()
                        } else {
                            cursor.ensureHidden()
                            animation.update(build)

                            platformProgressReporter.update(
                                state = PlatformProgressReporter.Progress.Percentage(
                                    ratio = build.completeTasksCount.toFloat() / build.totalTasksCount,
                                )
                            )
                        }

                        lastUpdated = timeSource.markNow()
                    }
                    delay(WidgetTimings.WidgetMinFrameUpdateInterval)
                }
            } finally {
                hideAnimation()
            }
        }
    }

    override fun emit(event: GlobalScopedEvent) {
        if (event is GlobalScopedEvent.BuildFinished) {
            stateTracker.builds.find { it.buildId == event.id }?.let(::printEpilogue)
        }
        stateTracker.emit(event)
    }

    private fun hideAnimation() {
        animation.clear()
        cursor.ensureShown()
        platformProgressReporter.update(PlatformProgressReporter.Progress.Hidden)
    }

    private fun printEpilogue(state: BuildState) {
        if (state.testStatistics.started) {
            terminal.println(state.testStatistics.render(terminal = terminal))
        }
    }
}
