/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core.downloader

import kotlinx.coroutines.delay
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.payload.ProgressState
import org.jetbrains.amper.events.sink.EventSink
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class DownloadProgressTracker(
    private val eventSink: EventSink<OperationScopedEvent.ProgressUpdated>,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    @Volatile private var bytesReceived: Long = 0L
    @Volatile private var contentLength: Long? = null

    suspend fun run() {
        val speedTracker = DownloadSpeedTracker(timeSource)
        while (true) {
            eventSink.emit(
                OperationScopedEvent.ProgressUpdated(
                    ProgressState.Downloading(
                        bytesDone = bytesReceived,
                        bytesTotal = contentLength,
                        speed = speedTracker.track(bytesReceived),
                    )
                )
            )
            delay(PROGRESS_UPDATE_INTERVAL)
        }
    }

    fun onDownload(bytesReceived: Long, contentLength: Long?) {
        this.bytesReceived = bytesReceived
        if (contentLength != null) {
            this.contentLength = contentLength
        }
    }
}

private val PROGRESS_UPDATE_INTERVAL = 100.milliseconds
