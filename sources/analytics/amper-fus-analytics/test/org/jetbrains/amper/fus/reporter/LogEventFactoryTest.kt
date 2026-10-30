/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.reporter

import org.jetbrains.amper.fus.schema.FusConsentState
import org.jetbrains.amper.fus.schema.FusEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogEventFactoryTest {

    @Test
    fun regularEventContainsIds() {
        val factory = LogEventFactory(machineIdProvider = { "my-machine" }, projectIdProvider = { "my-project" })

        val event = factory.create(FusEvents.CommandInvoked, emptyMap())

        assertEquals(mapOf("machine_id" to "my-machine", "project_id" to "my-project"), event.ids)
        assertNotNull(event.session)
    }

    @Test
    fun consentStateEventContainsNoIdentifier() {
        val factory = LogEventFactory(
            machineIdProvider = { error("the machine ID should not be computed for the consent state event") },
            projectIdProvider = { error("the project ID should not be accessed for the consent state event") },
        )

        val event = factory.create(
            FusEvents.ConsentState,
            FusEvents.ConsentState.buildEventData { consentState = FusConsentState.Rejected },
        )

        assertEquals(mapOf("machine_id" to "anonymous"), event.ids)
        assertNull(event.session)
        assertNull(event.system_data)
        assertEquals("ktc.anonymous", event.group.id)
        assertTrue(event.group.state)
        assertEquals("ktc.anonymous.consent.state", event.event.id)
        assertEquals(mapOf<String, Any>("consent_state" to "rejected"), event.event.data)
    }

    @Test
    fun consentStateEventDoesNotConsumeSystemEventIds() {
        val factory = LogEventFactory(machineIdProvider = { "my-machine" }, projectIdProvider = { null })

        val first = factory.create(FusEvents.CommandInvoked, emptyMap())
        factory.create(FusEvents.ConsentState, emptyMap())
        val second = factory.create(FusEvents.CommandInvoked, emptyMap())

        assertEquals("0", first.system_data?.get("system_event_id"))
        assertEquals("1", second.system_data?.get("system_event_id"))
    }
}
