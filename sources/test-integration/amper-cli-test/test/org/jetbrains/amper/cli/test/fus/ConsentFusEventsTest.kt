/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.fus

import com.jetbrains.fus.reporting.model.lion4.LogEvent
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Consent state (ktc.anonymous.consent.state)
 *
 * These tests need their own shared cache dir, because the consent state is reported at most once per day, and
 * that is tracked in <sharedCacheDir>/fus. They use a cheap command to avoid the cost of a cold cache.
 */
@Tag("cli-test-group-fus")
class ConsentFusEventsTest : AbstractFusEventsTest() {

    companion object {
        private val expectedAnonymousOsFamily = when (SystemInfo.CurrentHost.family) {
            OsFamily.Windows -> "windows"
            OsFamily.MacOs -> "macos"
            OsFamily.FreeBSD,
            OsFamily.Linux,
            OsFamily.Solaris
                -> "unix_like"
        }
    }

    @Test
    fun `consent state event is reported when the consent was accepted`() = runSlowTest {
        val sharedCacheDir = newIsolatedFusCacheDir()
        val result = runCliWithFus(
            projectDir = testProject(name = "jvm-hello-world"),
            "show", "modules",
            sharedCacheDir = sharedCacheDir,
        )

        val event = result.reportedFusEvents.assertSingle("ktc.anonymous.consent.state")
        assertEquals("ktc.anonymous", event.group.id)
        event.assertData(
            "consent_state" to "accepted",
            "ktc_version" to "LOCAL-SNAPSHOT",
            "os_family" to expectedAnonymousOsFamily,
            "interactive" to false,
            "caller_type" to "terminal",
        )
        assertNoIdentifier(event)
    }

    @Test
    fun `only the consent state event is reported when the consent was rejected`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-hello-world"), "show", "modules",
            consent = TestConsent.Rejected,
            sharedCacheDir = newIsolatedFusCacheDir(),
        )

        // Nothing but the consent state itself may be reported when the user rejected data collection.
        result.reportedFusEvents.assertEventIds("ktc.anonymous.consent.state")
        val event = result.reportedFusEvents.assertSingle("ktc.anonymous.consent.state")
        event.assertData(
            "consent_state" to "rejected",
            "ktc_version" to "LOCAL-SNAPSHOT",
            "os_family" to expectedAnonymousOsFamily,
            "interactive" to false,
            "caller_type" to "terminal",
        )
        assertNoIdentifier(event)
    }

    @Test
    fun `only the consent state event is reported when the consent was not answered`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-hello-world"), "show", "modules",
            consent = TestConsent.NotAnswered,
            sharedCacheDir = newIsolatedFusCacheDir(),
        )

        result.reportedFusEvents.assertEventIds("ktc.anonymous.consent.state")
        val event = result.reportedFusEvents.assertSingle("ktc.anonymous.consent.state")
        event.assertData(
            "consent_state" to "not_asked",
            "ktc_version" to "LOCAL-SNAPSHOT",
            "os_family" to expectedAnonymousOsFamily,
            "interactive" to false,
            "caller_type" to "terminal",
        )
        assertNoIdentifier(event)
    }

    @Test
    fun `the consent state event is not reported again right after a first report`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")
        val sharedCacheDir = newIsolatedFusCacheDir()

        val firstRun = runCliWithFus(projectDir, "show", "modules", sharedCacheDir = sharedCacheDir)
        firstRun.reportedFusEvents.assertSingle("ktc.anonymous.consent.state")

        val secondRun = runCliWithFus(projectDir, "show", "modules", sharedCacheDir = sharedCacheDir)
        secondRun.reportedFusEvents.assertNone("ktc.anonymous.consent.state")
        // the throttling must only affect the consent state event, not the regular ones
        secondRun.reportedFusEvents.assertSingle("ktc.command.invoked")
    }

    /**
     * Asserts that the given [event] cannot be linked to a user nor to any other event, which is the guarantee that
     * allows us to report it without consent. See `FusEvents.AnonymousGroup`.
     */
    private fun assertNoIdentifier(event: LogEvent) {
        assertEquals(mapOf("machine_id" to "anonymous"), event.ids, "The anonymous event must not identify the machine")
        assertNull(event.session, "The anonymous event must not have a session ID")
        assertNull(event.system_data, "The anonymous event must not have system data (it contains a sequence number)")
    }
}
