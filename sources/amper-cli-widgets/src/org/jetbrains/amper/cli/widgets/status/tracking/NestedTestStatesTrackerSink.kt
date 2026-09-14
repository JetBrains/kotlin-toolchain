/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import org.jetbrains.amper.cli.widgets.status.HasNestedStatusEntryStates
import org.jetbrains.amper.cli.widgets.status.StatusEntryState
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestEventWithDescriptor
import org.jetbrains.amper.testevents.TestEventWithId
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestSuiteAborted
import org.jetbrains.amper.testevents.TestSuiteFailed
import org.jetbrains.amper.testevents.TestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.jetbrains.amper.testevents.TestSuiteStarted
import java.util.concurrent.ConcurrentHashMap

/**
 * Test state tracking sink implementation.
 * Used as a root in [NestedStatusEntriesStateTrackerSink].
 *
 * Nested tests are modeled as [TestStatusEntryStateTrackerSink]s.
 */
internal open class NestedTestStatesTrackerSink(
    private val delegate: StatusTrackerDelegate,
    private val testId: TestId?,
) : EventSink<TestEvent>, HasNestedStatusEntryStates {
    private val testsMap = ConcurrentHashMap<TestId, TestStatusEntryStateTrackerSink>()

    override val nestedEntries: Collection<StatusEntryState>
        get() = testsMap.values

    override fun emit(event: TestEvent) {
        if (event !is TestEventWithId) {
            return  // Not interesting to the state tracker
        }

        if (event is TestEventWithDescriptor && event.descriptor.parentId != testId) {
            // Not for us, route to nested sinks, one might accept this
            testsMap.values.forEach { it.emit(event) }
            return
        }

        when (event) {
            is TestSuiteStarted -> {
                val theme = delegate.terminal.theme
                testsMap[event.testId] = TestStatusEntryStateTrackerSink(
                    delegate = delegate,
                    testId = event.testId,
                    renderedMoniker = theme.muted("suite ") + theme.success(event.descriptor.displayName),
                )
            }
            is TestStarted -> {
                testsMap[event.testId] = TestStatusEntryStateTrackerSink(
                    delegate = delegate,
                    testId = event.testId,
                    renderedMoniker = delegate.terminal.theme.success(event.descriptor.displayName),
                )
            }
            is TestSuiteFinished, is TestSuiteAborted, is TestSuiteFailed -> {
                // We don't control test events, so we don't assert their consistency here
                testsMap.remove(event.testId)
            }
            is TestFinished -> {
                // We don't control test events, so we don't assert their consistency here
                testsMap.remove(event.testId)
            }
            is TestSkipped, is TestSuiteSkipped, is TestReportEvent -> return  // don't affect the status
        }
        delegate.onStateUpdated()
    }
}
