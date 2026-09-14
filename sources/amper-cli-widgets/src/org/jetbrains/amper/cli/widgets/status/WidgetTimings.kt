/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal object WidgetTimings {
    /**
     * Minimal interval between widget frame updates,
     * i.e. widget can't update more frequently than this.
     */
    internal val WidgetMinFrameUpdateInterval = 1.seconds / 30  // 30 fps max

    /**
     * Maximum interval between widget frame updates,
     * i.e., if there were no updates to the widget for at least this amount of time (no changes)
     * then it should be re-rendered.
     *
     * This is needed to update visible time counters.
     */
    internal val WidgetMaxFrameUpdateInterval = 100.milliseconds

    /**
     * Spinner change frames after this amount of time.
     */
    internal val SpinnerTickInterval = 100.milliseconds

    /**
     * Every time a task/operation starts, it's not immediately shown because it may be very fast/cached,
     * and we don't want fast/cached operations to flicker in the status.
     *
     * So the status line should appear only after this time has passed.
     */
    internal val StatusEntryAppearDelay = 200.milliseconds
}