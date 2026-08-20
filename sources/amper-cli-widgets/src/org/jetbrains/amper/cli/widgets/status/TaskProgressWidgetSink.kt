/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.amper.cli.widgets.PlatformProgressReporter
import org.jetbrains.amper.cli.widgets.TerminalCursorManager
import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.GlobalScopedEvent
import org.jetbrains.amper.events.OperationId
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.GlobalEventSink
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.jetbrains.amper.testevents.TestSuiteAborted
import org.jetbrains.amper.testevents.TestSuiteFailed
import org.jetbrains.amper.testevents.TestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.jetbrains.amper.testevents.TestSuiteStarted
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [GlobalEventSink] that consumes events and renders a status widget as an animation.
 */
class TaskProgressWidgetSink(
    private val terminal: Terminal,
    coroutineScope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : GlobalEventSink {
    private val events = Channel<GlobalScopedEvent>(capacity = Channel.UNLIMITED)

    override fun emit(event: GlobalScopedEvent) {
        check(events.trySend(event).isSuccess)
    }

    private class TestTrackingState(
        val descriptor: TestDescriptor,
        val statusEntryState: StatusEntryState,
    )

    private val cursor = TerminalCursorManager(terminal)
    private val platformProgressReporter = PlatformProgressReporter(terminal)
    private var buildState: BuildState? = null
    private val testTrackingStates: MutableMap<TestId, TestTrackingState> = mutableMapOf()

    private val theme get() = terminal.theme

    private val animation = terminal.animation<BuildState> { state ->
        state.render(terminal = terminal)
    }

    init {
        // IMPORTANT: We use mutable non-synchronized state, so we ensure the access is single-threaded.
        coroutineScope.launch(Dispatchers.IO.limitedParallelism(1)) {
            launch {  // Job: listen to events, update the state and trigger animation updates
                try {
                    for (event in events) {
                        if (handleGlobalEvent(event)) {
                            updateWidget()
                        }
                    }
                } finally {
                    hideAnimation()
                }
            }

            launch {  // Job: update elapsed time and progress ticks
                fun updateOperations(statusEntryStates: Collection<StatusEntryState>) {
                    statusEntryStates.forEach { operation ->
                        operation.elapsed = operation.startTime.elapsedNow().inWholeSeconds.seconds
                        operation.ticks++
                        updateOperations(operation.childEntries.values)
                    }
                }

                while (true) {
                    buildState?.let {
                        updateOperations(it.taskStates.values)
                        updateWidget()
                    }
                    delay(100.milliseconds)
                }
            }
        }
    }

    context(scope: CoroutineScope)
    private fun handleGlobalEvent(event: GlobalScopedEvent): Boolean = when (event) {
        is GlobalScopedEvent.BuildStarted -> {
            check(buildState == null) { "Multiple builds are not supported per one widget" }
            buildState = BuildState(
                buildId = event.id,
                totalTasksCount = event.totalTasksCount,
            )
            true
        }
        is GlobalScopedEvent.BuildFinished -> {
            val finishedBuild = buildState
            check(finishedBuild?.buildId == event.id) { "Invalid $event: no such build" }
            printEpilogue(finishedBuild)
            buildState = null
            true
        }
        is GlobalScopedEvent.BuildEvent -> {
            handleBuildEvent(
                state = checkNotNull(buildState) { "$event is unexpected without an active build" },
                event = event.event,
            )
        }
    }

    context(scope: CoroutineScope)
    private fun handleBuildEvent(state: BuildState, event: BuildScopedEvent): Boolean = when (event) {
        is BuildScopedEvent.TaskStarted -> {
            state.taskStates[event.id] = StatusEntryState(
                startTime = timeSource.markNow(),
                renderedMoniker = event.monikerSpec.render(terminal = terminal),
                isInteractive = event.isInteractive,
            )
            // Do not show and update immediately for very fast tasks
            scope.launch {
                delay(200.milliseconds)
                state.taskStates[event.id]?.let {
                    it.shown = true
                    updateWidget()
                }
            }
            // If interactive - update immediately to hide the widget
            event.isInteractive
        }
        is BuildScopedEvent.TaskFinished -> {
            checkNotNull(state.taskStates.remove(event.id)) { "Invalid $event: no such task" }
            state.completeTasksCount += 1
            true
        }
        is BuildScopedEvent.TaskEvent -> state.taskStates[event.id]?.let {
            handleOperationEvent(it, event.event, buildState = state)
        } ?: false
    }

    context(buildState: BuildState)
    private fun handleOperationEvent(state: StatusEntryState, event: OperationScopedEvent): Boolean = when (event) {
        is OperationScopedEvent.Started -> {
            state.childEntries[event.id.asEntryId()] = StatusEntryState(
                startTime = timeSource.markNow(),
                shown = true,  // nested operations are immediately shown
                renderedMoniker = event.moniker,
            )
            state.shown = true // If a nested operation is started, we immediately show the task
            true
        }
        is OperationScopedEvent.ChildOperationEvent -> handleOperationEvent(
            state = checkNotNull(state.childEntries[event.id.asEntryId()]) { "Invalid $event: no such task/operation" },
            event = event.event,
        )
        is OperationScopedEvent.Finished -> state.childEntries.remove(event.id.asEntryId()) != null
        is OperationScopedEvent.DomainEvent -> when (event) {
            is TestEvent -> handleTestEvent(state, event)
            else -> false  // unknown domain events don't influence the progress widget
        }
    }

    context(buildState: BuildState)
    private fun handleTestEvent(rootState: StatusEntryState, event: TestEvent): Boolean {
        /** [rootState] is a top-most task/operation state, but we need to find the test-suite if any */
        fun parentTestStatusEntryState(descriptor: TestDescriptor) =
            descriptor.parentId?.let { testTrackingStates[it]?.statusEntryState } ?: rootState

        // Show test statistics on the very first test event
        val testStatistics = buildState.testStatistics
            ?: TestStatistics().also { buildState.testStatistics = it }
        return when (event) {
            is TestSuiteStarted, is TestStarted -> {
                val moniker = when (event) {
                    is TestStarted -> theme.success(event.descriptor.displayName)
                    is TestSuiteStarted -> theme.muted("suite ") + event.descriptor.displayName
                }
                val testStatusEntry = StatusEntryState(moniker, timeSource.markNow())
                parentTestStatusEntryState(event.descriptor).childEntries[event.testId.asEntryId()] = testStatusEntry
                testTrackingStates[event.testId] = TestTrackingState(
                    statusEntryState = testStatusEntry,
                    descriptor = event.descriptor,
                )
                true
            }
            is TestSuiteFinished, is TestSuiteFailed, is TestSuiteAborted -> {
                // TODO: Count failed/aborted test-suites in the stats somehow
                val testId = event.testId
                val testState = testTrackingStates[testId] ?: return false
                parentTestStatusEntryState(testState.descriptor).childEntries.remove(testId.asEntryId())
                // We don't control test events, so we don't assert their consistency here
                testTrackingStates.remove(testState.descriptor.id) != null
            }
            is TestFinished -> {
                with(testStatistics) {
                    when (event) {
                        is TestFinished.Aborted -> skipped++  // NOTE: We count aborted towards skipped
                        is TestFinished.Failed -> failed++
                        is TestFinished.Succeeded -> succeeded++
                    }
                }
                val testState = testTrackingStates[event.testId] ?: return false
                parentTestStatusEntryState(testState.descriptor).childEntries.remove(event.testId.asEntryId())
                // We don't control test events, so we don't assert their consistency here
                testTrackingStates.remove(testState.descriptor.id) != null
            }
            is TestSkipped, is TestSuiteSkipped -> {
                testStatistics.skipped++
                true
            }
            is TestReportEvent, is TestStderrEvent, is TestStdoutEvent -> false  // don't affect the status
        }
    }

    private fun updateWidget() {
        val state = buildState
        // Clear the widget if no build is active
        // or any task requires interactive access to the terminal
        if (state == null || state.taskStates.values.any { it.isInteractive }) {
            // Quick-fix: before the `RunTask` implementors are properly refactored to not be tasks,
            // we simply cancel the widget to not mess with the potentially interactive processes
            // that are launched from the task.
            hideAnimation()
        } else {
            cursor.ensureHidden()
            animation.update(state)

            platformProgressReporter.update(
                state = PlatformProgressReporter.Progress.Percentage(
                    ratio = state.completeTasksCount.toFloat() / state.totalTasksCount,
                )
            )
        }
    }

    private fun hideAnimation() {
        animation.clear()
        cursor.ensureShown()
        platformProgressReporter.update(PlatformProgressReporter.Progress.Hidden)
    }

    private fun printEpilogue(state: BuildState) {
        state.testStatistics?.let {
            terminal.println(it.render(terminal = terminal))
        }
    }

    private fun OperationId.asEntryId() = EntryId(value)
    private fun TestId.asEntryId() = EntryId(value)
}
