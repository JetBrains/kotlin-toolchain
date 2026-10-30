/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.consent

import org.jetbrains.amper.fus.cache.FusCache
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Provides a way to throttle the reporting of the consent state so it doesn't scale with the number of CLI invocations.
 *
 * Since we send this flag without user consent, we can't add any user-identifiable information to it.
 * This means we can't distinguish whether 2 events come from the same user, thus we need to make sure heavy users
 * don't send more events than casual users, so our aggregated count makes sense (e.g. 1 user calling the CLI 100 times
 * should not count as more than 100 users calling the CLI once).
 *
 * Throttling solves this by limiting the reporting of the consent to some period of time, so we can have a regular
 * probe instead of plain traffic numbers.
 */
class ConsentStateReportThrottle(
    private val fusCache: FusCache,
    private val minIntervalBetweenReports: Duration = 24.hours,
    private val clock: Clock = Clock.System,
) {
    /**
     * Returns whether the consent state should be reported now.
     */
    fun isReportDue(): Boolean {
        val lastReport = readLastReport() ?: return true
        val now = clock.now()
        // A last report more than one reporting period into the future means the clock was changed in a strange way,
        // we don't want to block reports until then.
        return now - lastReport >= minIntervalBetweenReports || lastReport > now + minIntervalBetweenReports
    }

    private fun readLastReport(): Instant? {
        val file = fusCache.consentStateLastReportFile
        if (!file.exists()) {
            return null
        }
        return file.readText().trim().toLongOrNull()?.let(Instant::fromEpochMilliseconds)
    }

    /**
     * Records that the consent state was just reported.
     */
    fun markReported() {
        fusCache.consentStateLastReportFile.writeText(clock.now().toEpochMilliseconds().toString())
    }
}
