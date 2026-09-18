/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.core.downloader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.testTimeSource
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class) // for advanceTimeBy() and testTimeSource
class DownloadSpeedTrackerTest {

    @Test
    fun `no speed is reported before one full second of download has elapsed`() = runTestWithMdc {
        val tracker = DownloadSpeedTracker(testTimeSource)

        advanceTimeBy(500.milliseconds)
        assertNull(tracker.track(500))

        advanceTimeBy(400.milliseconds)
        assertNull(tracker.track(900))

        advanceTimeBy(100.milliseconds) // exactly one second into the download
        assertEquals(1000, tracker.track(1000))
    }

    @Test
    fun `the speed is reported on every call after the initial window`() = runTestWithMdc {
        val tracker = DownloadSpeedTracker(testTimeSource)

        advanceTimeBy(2.seconds)
        assertEquals(1000, tracker.track(2000))

        advanceTimeBy(50.milliseconds)
        assertEquals(1000, tracker.track(2050))

        advanceTimeBy(50.milliseconds)
        assertEquals(1000, tracker.track(2100))
    }

    @Test
    fun `the speed of a constant-speed download is reported as is`() = runTestWithMdc {
        val tracker = DownloadSpeedTracker(testTimeSource)

        advanceTimeBy(2.seconds)
        assertEquals(1000, tracker.track(2000))

        advanceTimeBy(1.seconds)
        assertEquals(1000, tracker.track(3000))

        advanceTimeBy(1.seconds)
        assertEquals(1000, tracker.track(4000))
    }

    @Test
    fun `the reported speed follows the actual download speed`() = runTestWithMdc {
        val tracker = DownloadSpeedTracker(testTimeSource)

        // 2 seconds at 1000 B/s, with progress reported every 500ms
        advanceTimeBy(500.milliseconds)
        assertNull(tracker.track(500))
        advanceTimeBy(500.milliseconds)
        assertEquals(1000, tracker.track(1000))
        advanceTimeBy(500.milliseconds)
        assertEquals(1000, tracker.track(1500))
        advanceTimeBy(500.milliseconds)
        assertEquals(1000, tracker.track(2000))

        // The download speeds up to 10000 B/s, which is only reported as such once the window doesn't contain any
        // sample from the slower part of the download anymore.
        advanceTimeBy(500.milliseconds)
        assertEquals(5500, tracker.track(7000)) // 500 B for 0.5s, then 5000 B for 0.5s => 5500 B in 1s
        advanceTimeBy(500.milliseconds)
        assertEquals(10_000, tracker.track(12000)) // 10000 B in 1s
    }

    @Test
    fun `the reported speed decreases down to zero while the download is stalled`() = runTestWithMdc {
        val tracker = DownloadSpeedTracker(testTimeSource)

        // 2 seconds at 1000 B/s, with progress reported every 500ms
        advanceTimeBy(500.milliseconds)
        assertNull(tracker.track(500))
        advanceTimeBy(500.milliseconds)
        assertEquals(1000, tracker.track(1000))
        advanceTimeBy(500.milliseconds)
        assertEquals(1000, tracker.track(1500))
        advanceTimeBy(500.milliseconds)
        assertEquals(1000, tracker.track(2000))

        // The download stalls: the same total is reported again and again, and the speed decreases as the window
        // contains fewer and fewer of the bytes received before the stall.
        advanceTimeBy(500.milliseconds)
        assertEquals(500, tracker.track(2000)) // 500 B in 1s, as only half the window received bytes
        advanceTimeBy(500.milliseconds)
        assertEquals(0, tracker.track(2000)) // nothing received during the whole window
        advanceTimeBy(500.milliseconds)
        assertEquals(0, tracker.track(2000)) // nothing received during the whole window again
    }

    @ParameterizedTest
    @ValueSource(ints = [1000, 1500, 10_000])
    fun `the speed is zero when the download is stalled without any progress report`(stallMillis: Int) =
        runTestWithMdc {
            val tracker = DownloadSpeedTracker(testTimeSource)

            advanceTimeBy(2.seconds)
            assertEquals(1000, tracker.track(2000))

            // The server stops sending data altogether, so nothing is reported for at least the whole speed window.
            advanceTimeBy(stallMillis.milliseconds)
            assertEquals(0, tracker.track(2000))
        }
}
