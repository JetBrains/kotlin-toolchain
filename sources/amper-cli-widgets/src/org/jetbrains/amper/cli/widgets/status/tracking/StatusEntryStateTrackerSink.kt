/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import org.jetbrains.amper.cli.widgets.status.StatusEntryState
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.payload.ProgressState
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.ComparableTimeMark

/**
 * Tracks the state of an operation.
 *
 * Tests are tracked via [NestedTestStatesTrackerSink].
 */
internal open class StatusEntryStateTrackerSink(
    delegate: StatusTrackerDelegate,
    override val renderedMoniker: String,
) : NestedStatusEntriesStateTrackerSink(delegate), StatusEntryState {
    private val _progressState = AtomicReference<ProgressState>(ProgressState.Indeterminate)

    override val startTime: ComparableTimeMark = delegate.timeSource.markNow()
    override val showImmediately get() = false

    override val progressState: ProgressState get() = _progressState.get()

    override fun emit(event: OperationScopedEvent) {
        super.emit(event)
        when (event) {
            is OperationScopedEvent.ProgressUpdated -> {
                val oldState = _progressState.getAndSet(event.state)
                if (oldState != event.state) {
                    delegate.onStateUpdated()
                }
            }
            else -> Unit
        }
    }
}
