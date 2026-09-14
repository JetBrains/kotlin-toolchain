/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import org.jetbrains.amper.cli.widgets.status.TaskStatusEntryState

/**
 * Tracks the state of a task.
 *
 * Tests are tracked via [NestedTestStatesTrackerSink].
 */
internal open class TaskStatusEntryStateTrackerSink(
    delegate: StatusTrackerDelegate,
    renderedMoniker: String,
    override val isInteractive: Boolean,
) : StatusEntryStateTrackerSink(delegate, renderedMoniker), TaskStatusEntryState
