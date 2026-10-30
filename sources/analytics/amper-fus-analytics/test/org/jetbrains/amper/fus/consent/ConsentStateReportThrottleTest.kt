/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.consent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.testTimeSource
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.asClock

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentStateReportThrottleTest {

    @TempDir
    lateinit var fusRoot: Path

    private val start = Instant.parse("2026-10-30T10:00:00Z")

    @Test
    fun dueWhenNeverReported() {
        // no matter the clock, there is no file here
        assertTrue(newReporterThrottle(clock = Clock.System).isReportDue())
    }

    @Test
    fun notDueRightAtReportingTime() = runTestWithMdc {
        val clock = testTimeSource.asClock(start)
        newReporterThrottle(clock).markReported()
        assertFalse(newReporterThrottle(clock).isReportDue())
    }

    @Test
    fun dueRightAfterTheIntervalExpiredAndNotBefore() = runTestWithMdc {
        val clock = testTimeSource.asClock(start)
        newReporterThrottle(clock).markReported()
        advanceTimeBy(24.hours - 1.milliseconds)
        assertFalse(newReporterThrottle(clock).isReportDue(), "shouldn't be due right before the configured interval")
        advanceTimeBy(1.milliseconds)
        assertTrue(newReporterThrottle(clock).isReportDue(), "should be due right after the interval")
    }

    @Test
    fun dueWhenLastReportFileIsCorrupt() {
        (fusRoot / "consent-state-last-report").writeText("not a timestamp")
        assertTrue(newReporterThrottle(Clock.System).isReportDue(), "should be due when last report file is corrupt")
    }

    @Test
    fun dueWhenLastReportIsTooFarIntoTheFuture() = runTestWithMdc {
        val clock = testTimeSource.asClock(start)
        advanceTimeBy(24.hours + 1.milliseconds)
        newReporterThrottle(clock).markReported()
        val backToStartClock = testTimeSource.asClock(start)
        assertTrue(newReporterThrottle(backToStartClock).isReportDue(), "should be due if the last report time is too far into the future")
    }

    @Test
    fun notDueWhenLastReportIsJustALittleBitIntoTheFuture() = runTestWithMdc {
        val clock = testTimeSource.asClock(start)
        advanceTimeBy(10.seconds)
        newReporterThrottle(clock).markReported()
        val backToStartClock = testTimeSource.asClock(start)
        assertFalse(newReporterThrottle(backToStartClock).isReportDue(), "should not be due if the clock changed due to leap seconds")
    }

    private fun newReporterThrottle(clock: Clock): ConsentStateReportThrottle =
        ConsentStateReportThrottle(fusCache = FusCache(fusRoot), clock = clock)
}
