/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import org.jetbrains.amper.events.BuildId
import org.jetbrains.amper.events.TaskExecutionId
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration

/** Mutable state of the whole build */
internal class BuildState(
    val buildId: BuildId,
    val totalTasksCount: Int,
    var testStatistics: TestStatistics? = null,
    var completeTasksCount: Int = 0,
    val taskStates: MutableMap<TaskExecutionId, StatusEntryState> = mutableMapOf(),
)

/** State for tasks, operations, and testsuite/test executions */
internal class StatusEntryState(
    val renderedMoniker: String,
    val startTime: ComparableTimeMark,
    val isInteractive: Boolean = false,
    var elapsed: Duration = Duration.ZERO,
    var shown: Boolean = false,
    var ticks: Int = 0,
    val childEntries: MutableMap<EntryId, StatusEntryState> = mutableMapOf(),
)

@JvmInline
internal value class EntryId(val id: Any)

internal class TestStatistics(
    var succeeded: Int = 0,
    var failed: Int = 0,
    var skipped: Int = 0,
)
