/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import org.jetbrains.amper.events.BuildId
import org.jetbrains.amper.events.payload.ProgressState
import kotlin.time.ComparableTimeMark

/**
 * Mutable state of the whole build.
 */
internal interface BuildState {
    val buildId: BuildId

    /**
     * Total number of tasks planned for this build.
     */
    val totalTasksCount: Int

    /**
     * Number of tasks that have finished execution.
     * Can't exceed the [totalTasksCount] at any point.
     */
    val completeTasksCount: Int

    /**
     * Test statistics for the build.
     */
    val testStatistics: TestStatistics

    /**
     * Currently running tasks within the build.
     */
    val taskStates: Collection<TaskStatusEntryState>
}

internal interface HasNestedStatusEntryStates {
    /**
     * Nested status entries.
     */
    val nestedEntries: Collection<StatusEntryState>
}

/**
 * State for tasks, operations, and test suite/test executions.
 */
internal interface StatusEntryState : HasNestedStatusEntryStates {
    /**
     * A string with ANSI-sequences already encoded that represents the status entry.
     */
    val renderedMoniker: String

    /**
     * A time mark when the entry was *first tracked*.
     * Do not rely on this value for precise tracing.
     */
    val startTime: ComparableTimeMark

    /**
     * `true` if the status should be displayed immediately when tracked.
     * `false` instructs the UI to wait for some time before displaying the status;
     * this is done to prevent the jitter for potentially fast operations.
     */
    val showImmediately: Boolean

    /**
     * Progress status.
     */
    val progressState: ProgressState
}

/**
 * State for task executions.
 */
internal interface TaskStatusEntryState : StatusEntryState {
    /**
     * Whether this operation hosts an interactive (inherited IO) external process which needs full terminal
     * access.
     */
    val isInteractive: Boolean
}

internal interface TestStatistics {
    /**
     * `true` if there are tests that have already started in the [build][BuildState].
     * A good condition to understand when to show test statistics info to the user.
     */
    val started: Boolean

    /**
     * Number of successfully completed tests.
     */
    val succeeded: Int

    /**
     * Number of failed tests.
     */
    val failed: Int

    /**
     * Number of skipped (+ aborted) tests.
     */
    val skipped: Int
}
