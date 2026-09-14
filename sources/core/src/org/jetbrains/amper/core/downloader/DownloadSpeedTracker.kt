/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core.downloader

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

internal class DownloadSpeedTracker(
    private val elapsedTime: () -> Duration = TimeSource.Monotonic.markNow()::elapsedNow,
) {
    private val downloadStart = elapsedTime()
    private val speedWindow = ArrayDeque<DownloadSpeedSample>().apply {
        addLast(DownloadSpeedSample(downloadStart, 0L))
    }
    private var lastProgressUpdate = downloadStart

    fun track(bytesReceived: Long): Long? {
        val now = elapsedTime()
        if (now - lastProgressUpdate < PROGRESS_UPDATE_INTERVAL) return null
        lastProgressUpdate = now

        speedWindow.addLast(DownloadSpeedSample(now, bytesReceived))
        while (speedWindow.size > 1 && now - speedWindow.first().elapsedTime > DOWNLOAD_SPEED_WINDOW) {
            speedWindow.removeFirst()
        }

        val speed = if (now - downloadStart > DOWNLOAD_SPEED_WINDOW) {
            val oldest = speedWindow.first()
            val windowElapsed = now - oldest.elapsedTime
            val windowBytes = bytesReceived - oldest.bytesReceived
            (windowBytes / windowElapsed.toDouble(DurationUnit.SECONDS)).roundToLong()
        } else {
            null
        }
        return speed
    }
}

private data class DownloadSpeedSample(
    val elapsedTime: Duration,
    val bytesReceived: Long,
)

private val DOWNLOAD_SPEED_WINDOW = 1.seconds

private val PROGRESS_UPDATE_INTERVAL = 100.milliseconds
