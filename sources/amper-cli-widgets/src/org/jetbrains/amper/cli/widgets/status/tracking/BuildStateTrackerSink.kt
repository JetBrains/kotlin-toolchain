/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import org.jetbrains.amper.cli.widgets.status.BuildState
import org.jetbrains.amper.cli.widgets.status.TestStatistics
import org.jetbrains.amper.cli.widgets.status.render
import org.jetbrains.amper.events.BuildId
import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.TaskExecutionId
import org.jetbrains.amper.events.sink.BuildEventSink
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.jetbrains.amper.testevents.TestSuiteStarted
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class BuildStateTrackerSink(
    override val buildId: BuildId,
    override val totalTasksCount: Int,
    private val delegate: StatusTrackerDelegate,
) : BuildEventSink, BuildState, TestStatistics {
    private val _testsStarted = AtomicBoolean(false)
    private val _testsSucceeded = AtomicInteger(0)
    private val _testsSkipped = AtomicInteger(0)
    private val _testsFailed = AtomicInteger(0)

    private val _completeTasksCount = AtomicInteger(0)
    private val taskStatesMap = ConcurrentHashMap<TaskExecutionId, TaskStatusEntryStateTrackerSink>()

    override val startTime = delegate.timeSource.markNow()
    override val testStatistics get() = this
    override val completeTasksCount get() = _completeTasksCount.get()
    override val taskStates get() = taskStatesMap.values

    override val started get() = _testsStarted.get()
    override val succeeded get() = _testsSucceeded.get()
    override val failed get() = _testsFailed.get()
    override val skipped get() = _testsSkipped.get()

    override fun emit(event: BuildScopedEvent) {
        when (event) {
            is BuildScopedEvent.TaskStarted -> {
                taskStatesMap[event.id] = TaskStatusEntryStateTrackerSink(
                    delegate = delegate,
                    renderedMoniker = event.monikerSpec.render(terminal = delegate.terminal),
                    isInteractive = event.isInteractive,
                )
                // If interactive - update immediately to hide the widget
                if (event.isInteractive) {
                    delegate.onStateUpdated()
                }
            }
            is BuildScopedEvent.TaskFinished -> {
                checkNotNull(taskStatesMap.remove(event.id)) { "Invalid $event: no such task" }
                _completeTasksCount.incrementAndGet()
                delegate.onStateUpdated()
            }
            is BuildScopedEvent.TaskEvent -> {
                checkNotNull(taskStatesMap[event.id]) { "Invalid $event: no such task" }.emit(event.event)
                trackTestStatistics(event.event)
            }
        }
    }

    private fun trackTestStatistics(event: OperationScopedEvent) {
        when (event) {
            is OperationScopedEvent.ChildOperationEvent -> trackTestStatistics(event.event)
            is TestEvent -> when (event) {
                is TestSuiteStarted, is TestStarted -> {
                    // Signal that the test run has started
                    _testsStarted.set(true)
                }
                is TestSkipped, is TestSuiteSkipped, is TestFinished.Aborted -> {
                    // NOTE: We count aborted towards skipped
                    _testsSkipped.incrementAndGet()
                }
                is TestFinished.Failed -> _testsFailed.incrementAndGet()
                is TestFinished.Succeeded -> _testsSucceeded.incrementAndGet()
                else -> Unit // doesn't affect the test counts
            }
            else -> Unit
        }
    }
}
