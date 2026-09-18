/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core.downloader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.testTimeSource
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.payload.ProgressState
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.test.runTestWithMdc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class) // for advanceTimeBy() and testTimeSource
class DownloadProgressTrackerTest {

    @Test
    fun `reports zero speed while the download callback is stalled`() = runTestWithMdc {
        val eventSink = RecordingEventSink()
        val tracker = DownloadProgressTracker(eventSink, testTimeSource)
        val trackingJob = startTracking(tracker)

        advanceTimeBy(2_000.milliseconds)
        tracker.onDownload(bytesReceived = 2_000, contentLength = 2_000)
        advanceTimeBy(100.milliseconds)
        runCurrent()

        advanceTimeBy(1_000.milliseconds)
        runCurrent()

        assertEquals(0L, eventSink.downloads.last().speed)
        trackingJob.cancel()
    }

    @Test
    fun `reports progress without speed before the speed window is full`() = runTestWithMdc {
        val eventSink = RecordingEventSink()
        val tracker = DownloadProgressTracker(eventSink, testTimeSource)
        val trackingJob = startTracking(tracker)

        advanceTimeBy(500.milliseconds)
        tracker.onDownload(bytesReceived = 500, contentLength = 1_000)
        advanceTimeBy(100.milliseconds)
        runCurrent()

        assertEquals(500L, eventSink.downloads.last().bytesDone)
        assertNull(eventSink.downloads.last().speed)
        trackingJob.cancel()
    }

    @Test
    fun `throttles consecutive download callbacks`() = runTestWithMdc {
        val eventSink = RecordingEventSink()
        val tracker = DownloadProgressTracker(eventSink, testTimeSource)
        val trackingJob = startTracking(tracker)

        advanceTimeBy(1_000.milliseconds)
        tracker.onDownload(bytesReceived = 1_000, contentLength = 2_000)
        advanceTimeBy(50.milliseconds)
        tracker.onDownload(bytesReceived = 1_050, contentLength = 2_000)
        advanceTimeBy(50.milliseconds)
        runCurrent()

        assertEquals([1_000L, 1_050L], eventSink.downloads.dropWhile { it.bytesDone == 0L }.map { it.bytesDone })
        trackingJob.cancel()
    }

    @Test
    fun `repeated sampled updates report the latest download state`() = runTestWithMdc {
        val eventSink = RecordingEventSink()
        val tracker = DownloadProgressTracker(eventSink, testTimeSource)
        val trackingJob = startTracking(tracker)

        advanceTimeBy(1_000.milliseconds)
        tracker.onDownload(bytesReceived = 1_000, contentLength = 3_000)
        advanceTimeBy(100.milliseconds)
        runCurrent()
        advanceTimeBy(50.milliseconds)
        tracker.onDownload(bytesReceived = 1_050, contentLength = 3_000)
        advanceTimeBy(50.milliseconds)
        runCurrent()

        assertEquals([1_000L, 1_050L], eventSink.downloads.takeLast(2).map { it.bytesDone })
        trackingJob.cancel()
    }

    private fun TestScope.startTracking(tracker: DownloadProgressTracker): Job =
        backgroundScope.launch { tracker.run() }

    private class RecordingEventSink : EventSink<OperationScopedEvent.ProgressUpdated> {
        val downloads = mutableListOf<ProgressState.Downloading>()

        override fun emit(event: OperationScopedEvent.ProgressUpdated) {
            downloads += event.state as ProgressState.Downloading
        }
    }
}