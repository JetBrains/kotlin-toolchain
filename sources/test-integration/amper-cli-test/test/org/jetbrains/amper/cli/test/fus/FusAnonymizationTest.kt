/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.fus

import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.absolute
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shape of the machine ID, which `MachineIdProvider` already hashes before the CLI reports it. */
private val Sha256HexRegex = Regex("""[0-9a-f]{64}""")

/**
 * Tests the anonymization that the FUS client applies to the identifiers of the events before sending them.
 *
 * The CLI deliberately reports some identifiers that it cannot anonymize itself (the project path, in particular),
 * so these tests compare the reported events with the ones that the FUS client recorded, which are the ones that
 * are effectively sent to the FUS servers.
 */
@Tag("cli-test-group-fus")
class FusAnonymizationTest : AbstractFusEventsTest() {

    @Test
    fun `the project path is reported but only sent as an anonymous hash`() = runSlowTest {
        // 'ktc.command.invoked.build' is the first event reported once the project is known, and unlike
        // 'ktc.command.result' it is not the last event of the run, which the FUS client sometimes fails to record.
        val projectDir = testProject("jvm-hello-world")
        val result = runCliWithFus(projectDir, "build")

        val projectPath = projectDir.absolute().invariantSeparatorsPathString
        val reported = result.reportedFusEvents.assertSingle("ktc.command.invoked.build")
        assertEquals(projectPath, reported.ids["project_id"], "The CLI reports the raw path, see 'setProject'")

        val sent = result.validatedFusEvents.assertSingle("ktc.command.invoked.build")
        assertIsAnonymizedId(sent.ids["project_id"], idName = "project_id")
        val sentJson = sent.toJson()
        assertFalse(projectPath in sentJson, "The project path must not appear in the sent event:\n$sentJson")
        assertFalse(
            projectDir.name in sentJson,
            "Not even the project directory name may appear in the sent event:\n$sentJson",
        )
    }

    @Test
    fun `the session ID is anonymized while the machine ID is sent as is`() = runSlowTest {
        val result = runCliWithFus(testProject("jvm-hello-world"), "show", "modules")

        val reported = result.reportedFusEvents.assertSingle("ktc.command.invoked")
        val sent = result.validatedFusEvents.assertSingle("ktc.command.invoked")

        assertNotNull(reported.session, "The CLI should report a session ID for regular events")
        assertIsAnonymizedId(sent.session, idName = "session")
        assertNotEquals(reported.session, sent.session, "The session ID should not be sent as the CLI reported it")

        // The machine ID is excluded from the anonymization (see 'skipAnonymizationIds') because it is already an
        // anonymous hash, and keeping it as is allows matching these events with those of other JetBrains products.
        assertEquals(
            reported.ids["machine_id"],
            sent.ids["machine_id"],
            "The machine ID should be sent exactly as the CLI reported it",
        )
        val machineId = assertNotNull(sent.ids["machine_id"], "The machine ID is required in regular events")
        assertTrue(
            Sha256HexRegex.matches(machineId),
            "The machine ID should be a plain SHA-256 hash, without the suffix added by the anonymization, " +
                "but was '$machineId'",
        )
    }

    @Test
    fun `the consent state event is sent without any identifier`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-hello-world"), "show", "modules",
            sharedCacheDir = newIsolatedFusCacheDir(),
        )

        val sent = result.validatedFusEvents.assertSingle("ktc.anonymous.consent.state")
        // The machine ID is a required field, so the CLI reports the same constant for everyone. Anonymizing it here
        // would turn it back into a per-user value and defeat its purpose, hence the 'skipAnonymizationIds' setting.
        assertEquals(
            mapOf("machine_id" to "anonymous"),
            sent.ids,
            "The anonymous event must not identify the machine, even after anonymization",
        )
        assertNull(sent.session, "The anonymous event must not have a session ID")
        assertNull(sent.system_data, "The anonymous event must not have system data (it contains a sequence number)")
    }
}
