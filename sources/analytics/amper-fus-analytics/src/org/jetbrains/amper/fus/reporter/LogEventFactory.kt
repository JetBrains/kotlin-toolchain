/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.reporter

import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.model.lion4.LogEventAction
import com.jetbrains.fus.reporting.model.lion4.LogEventGroup
import com.jetbrains.fus.reporting.model.lion4.LogEventSystemData
import com.jetbrains.fus.reporting.model.userIdToBucket
import com.jetbrains.fus.reporting.schema.BaseEventId
import com.jetbrains.fus.reporting.schema.EventLogGroup
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.fus.FusConstants
import org.jetbrains.amper.fus.schema.FusEvents
import org.jetbrains.amper.fus.schema.KtcFusRecorder
import org.jetbrains.amper.fus.schema.MACHINE_ID_KEY
import org.jetbrains.amper.fus.schema.PROJECT_ID_KEY
import org.jetbrains.amper.fus.userid.isInternalUserForFUS
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The machine ID used in events that must not identify the user.
 * It is a fixed value for all users, so we can't distinguish users based on this.
 */
private const val ANONYMOUS_MACHINE_ID = "anonymous"

internal class LogEventFactory(
    machineIdProvider: () -> String,
    private val projectIdProvider: () -> String?,
    private val clock: Clock = Clock.System,
) {
    private val sessionId: String = Uuid.random().toString()
    private val systemEventId = AtomicLong()

    private val machineId: String by lazy(machineIdProvider)
    private val bucket: Int by lazy { userIdToBucket(machineId) }

    fun create(event: BaseEventId, data: Map<String, Any>): LogEvent =
        if (event.group.id == FusEvents.AnonymousGroup.id) {
            createAnonymous(event, data)
        } else {
            createPseudonymized(event, data)
        }

    private fun createPseudonymized(event: BaseEventId, data: Map<String, Any>): LogEvent = LogEvent(
        recorder = KtcFusRecorder,
        product = FusConstants.PRODUCT_CODE,
        ids = buildMap {
            put(MACHINE_ID_KEY, machineId)
            projectIdProvider()?.let {
                put(PROJECT_ID_KEY, it)
            }
        },
        internal = isInternalUserForFUS,
        time = clock.now().toEpochMilliseconds(),
        build = AmperBuild.mavenVersion, // TODO check this, should we trim something?
        session = sessionId,
        group = event.group.toLogEventGroup(),
        bucket = bucket,
        event = event.toLogEventAction(data),
        system_data = LogEventSystemData(system_event_id = systemEventId.getAndIncrement().toString()).toMap().orEmpty()
    )

    /**
     * Creates an event without any identifier: no real machine ID, no project ID, no session, no system event ID, and
     * a fixed bucket. This is used for events that are sent regardless of the user's consent.
     */
    private fun createAnonymous(event: BaseEventId, data: Map<String, Any>): LogEvent = LogEvent(
        recorder = KtcFusRecorder,
        product = FusConstants.PRODUCT_CODE,
        // The FUS client requires at least one ID, so we provide it, but we use the same constant for everyone
        ids = mapOf(MACHINE_ID_KEY to ANONYMOUS_MACHINE_ID),
        internal = isInternalUserForFUS,
        time = clock.now().toEpochMilliseconds(),
        build = AmperBuild.mavenVersion,
        session = null,
        group = event.group.toLogEventGroup(),
        bucket = Random.nextInt(0, 256),
        event = event.toLogEventAction(data),// we spread events evenly across buckets, without basing it on any ID
        system_data = null,
    )

    private fun BaseEventId.toLogEventAction(data: Map<String, Any>) = LogEventAction(
        id = eventId,
        data = data.toMutableMap(),
    )

    private fun EventLogGroup.toLogEventGroup(): LogEventGroup = LogEventGroup(
        id = id,
        version = version,
        state = type == EventLogGroup.Type.STATE,
    )
}