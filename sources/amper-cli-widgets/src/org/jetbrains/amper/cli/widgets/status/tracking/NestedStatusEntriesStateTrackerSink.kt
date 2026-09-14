/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import org.jetbrains.amper.cli.widgets.status.HasNestedStatusEntryStates
import org.jetbrains.amper.cli.widgets.status.StatusEntryState
import org.jetbrains.amper.events.OperationId
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.testevents.TestEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the nested operation of a task/operation.
 *
 * Nested operations are modeled as [StatusEntryStateTrackerSink]
 * Tests are tracked via [NestedTestStatesTrackerSink].
 */
internal open class NestedStatusEntriesStateTrackerSink(
    protected val delegate: StatusTrackerDelegate,
) : OperationEventSink, HasNestedStatusEntryStates {
    private val operationsMap = ConcurrentHashMap<OperationId, StatusEntryStateTrackerSink>()
    private val testSink = NestedTestStatesTrackerSink(delegate = delegate, testId = null)

    override val nestedEntries: Collection<StatusEntryState> =
        ConcatenatedCollectionView(operationsMap.values, testSink.nestedEntries)

    override fun emit(event: OperationScopedEvent) {
        when (event) {
            is OperationScopedEvent.Started -> {
                operationsMap[event.id] = StatusEntryStateTrackerSink(
                    delegate = delegate,
                    renderedMoniker = event.moniker,
                )
                delegate.onStateUpdated()
            }
            is OperationScopedEvent.Finished -> {
                checkNotNull(operationsMap.remove(event.id)) {
                    "Invalid $event: no such task/operation"
                }
                delegate.onStateUpdated()
            }
            is OperationScopedEvent.ChildOperationEvent -> {
                checkNotNull(operationsMap[event.id]) {
                    "Invalid $event: no such task/operation"
                }.emit(event.event)
            }
            is OperationScopedEvent.DomainEvent -> when (event) {
                is TestEvent -> testSink.emit(event)
                else -> Unit  // unknown domain events don't influence the status widget
            }
            is OperationScopedEvent.ProgressUpdated -> Unit
        }
    }

    private class ConcatenatedCollectionView<T>(
        private vararg val collections: Collection<T>,
    ) : AbstractCollection<T>() {
        private val sequence = collections.asSequence().flatMap { it.asSequence() }
        override fun iterator() = sequence.iterator()
        override val size get() = collections.sumOf { it.size }
    }
}
