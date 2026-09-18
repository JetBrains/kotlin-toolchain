/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core.downloader

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

internal class DownloadSpeedTracker(
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * This is mainly used as an origin of time to measure elapsed time differences.
     * But it's still semantically the download start because the first sample is artificially at "elapsed time 0" from
     * this origin.
     */
    private val downloadStart = timeSource.markNow()

    private val speedWindow = ArrayDeque<DownloadSpeedSample>().apply {
        addLast(DownloadSpeedSample(elapsedTime = Duration.ZERO, bytesReceived = 0L))
    }

    /**
     * Records that a total of [bytesReceived] bytes have been received so far, and returns the average download speed
     * over the last [DOWNLOAD_SPEED_WINDOW] (in bytes per second), or null if the speed shouldn't be reported yet.
     *
     * The speed is not reported until the download has been running for at least [DOWNLOAD_SPEED_WINDOW] (so the first
     * samples are not skewed by the connection setup). Samples are recorded on every call, and the reported speed is
     * averaged over the last [DOWNLOAD_SPEED_WINDOW] of the download.
     */
    fun track(bytesReceived: Long): Long? {
        val elapsed = downloadStart.elapsedNow()
        speedWindow.addLast(DownloadSpeedSample(elapsed, bytesReceived))
        // We keep at least 2 samples, so we can report a speed even when no bytes were reported for longer than
        // DOWNLOAD_SPEED_WINDOW. We also make sure to keep samples so we have at least a full window.
        while (speedWindow.size > 2 && elapsed - speedWindow[1].elapsedTime >= DOWNLOAD_SPEED_WINDOW) {
            speedWindow.removeFirst()
        }

        if (elapsed < DOWNLOAD_SPEED_WINDOW) return null

        val oldest = speedWindow.first()
        val windowElapsed = elapsed - oldest.elapsedTime
        val windowBytes = bytesReceived - oldest.bytesReceived
        return (windowBytes / windowElapsed.toDouble(DurationUnit.SECONDS)).roundToLong()
    }
}

private data class DownloadSpeedSample(
    val elapsedTime: Duration,
    val bytesReceived: Long,
)

private val DOWNLOAD_SPEED_WINDOW = 1.seconds
