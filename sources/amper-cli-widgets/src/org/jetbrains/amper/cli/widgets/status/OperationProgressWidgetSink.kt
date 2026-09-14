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
import org.jetbrains.amper.cli.widgets.TerminalCursorManager
import org.jetbrains.amper.cli.widgets.status.tracking.NestedStatusEntriesStateTrackerSink
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.OperationEventSink
import kotlin.time.TimeSource

/**
 * [OperationEventSink] that consumes events and renders a status widget as an animation.
 *
 * Similar to [ProgressWidgetSink] but since it is already operation-scoped,
 * it doesn't track overall build progress, test statistics and global progress bar.
 *
 * Use [standaloneOperationProgressWidget] to properly instantiate and use this functionality.
 */
class OperationProgressWidgetSink(
    private val terminal: Terminal,
    coroutineScope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : OperationEventSink {
    private val delegate = StatusTrackerDelegateImpl(terminal, timeSource)
    private val stateTracker = NestedStatusEntriesStateTrackerSink(delegate = delegate)
    private val cursor = TerminalCursorManager(terminal)

    private val animation = terminal.animation<Collection<StatusEntryState>> { state ->
        state.renderEntries(timeSource, terminal = terminal)
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
                        val entries = stateTracker.nestedEntries
                        if (entries.isEmpty()) {
                            // Clear the widget if no operations are active
                            hideAnimation()
                        } else {
                            cursor.ensureHidden()
                            animation.update(entries)
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

    override fun emit(event: OperationScopedEvent) {
        stateTracker.emit(event)
    }

    private fun hideAnimation() {
        animation.clear()
        cursor.ensureShown()
    }
}
