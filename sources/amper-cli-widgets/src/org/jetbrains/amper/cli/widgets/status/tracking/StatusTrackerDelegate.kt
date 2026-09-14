/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import com.github.ajalt.mordant.terminal.Terminal
import kotlin.time.TimeSource

/**
 * Required API for the event-based build state trackers to function.
 */
internal interface StatusTrackerDelegate {
    val terminal: Terminal
    val timeSource: TimeSource.WithComparableMarks

    /**
     * Called when the state is updated (any nested part of the state).
     *
     * *Thread safety: can be called from an arbitrary thread.*
     */
    fun onStateUpdated()
}
