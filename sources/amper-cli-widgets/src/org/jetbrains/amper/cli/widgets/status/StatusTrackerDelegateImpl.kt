/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.cli.widgets.status.tracking.StatusTrackerDelegate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource

/**
 * Default implementation for the [StatusTrackerDelegate] that maintains a "dirty" status using [consumeUpdate]
 */
internal class StatusTrackerDelegateImpl(
    override val terminal: Terminal,
    override val timeSource: TimeSource.WithComparableMarks,
) : StatusTrackerDelegate {
    private val needsUpdating = AtomicBoolean(false)

    override fun onStateUpdated() {
        needsUpdating.set(true)
    }

    /**
     * Returns whether there were updates since the last query and clears the "dirty" status.
     */
    fun consumeUpdate(): Boolean = needsUpdating.getAndSet(false)
}
