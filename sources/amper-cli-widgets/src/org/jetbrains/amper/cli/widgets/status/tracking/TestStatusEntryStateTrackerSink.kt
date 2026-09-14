/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import org.jetbrains.amper.cli.widgets.status.StatusEntryState
import org.jetbrains.amper.events.payload.ProgressState
import org.jetbrains.amper.testevents.TestId
import kotlin.time.ComparableTimeMark

internal class TestStatusEntryStateTrackerSink(
    delegate: StatusTrackerDelegate,
    override val renderedMoniker: String,
    testId: TestId,
) : NestedTestStatesTrackerSink(delegate, testId), StatusEntryState {
    override val showImmediately get() = true
    override val progressState get() = ProgressState.Indeterminate
    override val startTime: ComparableTimeMark = delegate.timeSource.markNow()
}
