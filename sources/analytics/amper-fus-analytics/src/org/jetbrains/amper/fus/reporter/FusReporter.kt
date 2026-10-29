/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.reporter

import com.jetbrains.fus.reporting.FusClient
import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.model.lion4.LogEventAction
import com.jetbrains.fus.reporting.model.lion4.LogEventGroup
import com.jetbrains.fus.reporting.model.lion4.LogEventSystemData
import com.jetbrains.fus.reporting.model.userIdToBucket
import com.jetbrains.fus.reporting.schema.BaseEventId
import com.jetbrains.fus.reporting.schema.EventId
import com.jetbrains.fus.reporting.schema.EventLogGroup
import com.jetbrains.fus.reporting.schema.FieldListDescription
import com.jetbrains.fus.reporting.schema.VarargEventId
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.fus.FusConstants
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.fus.client.createFusClient
import org.jetbrains.amper.fus.schema.KtcFusRecorder
import org.jetbrains.amper.fus.schema.MACHINE_ID_KEY
import org.jetbrains.amper.fus.schema.PROJECT_ID_KEY
import org.jetbrains.amper.fus.userid.MachineIdProvider
import org.jetbrains.amper.fus.userid.isInternalUserForFUS
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Allows logging feature usage statistics.
 */
interface FusReporter {

    /**
     * Reports the given [event] with the given [data].
     */
    fun log(event: BaseEventId, data: Map<String, Any>)

    /**
     * Reports the given dataless [event].
     */
    fun log(event: EventId) {
        log(event, emptyMap())
    }

    /**
     * Reports the given [event], configuring its data using [buildData].
     */
    fun <D : FieldListDescription> log(event: VarargEventId<D>, buildData: D.() -> Unit) {
        log(event, event.buildEventData(buildData))
    }

    /**
     * Closes the reporter and flushes all pending events.
     */
    fun close()

    companion object {
        /**
         * Creates a [FusReporter] that uses the given [FusCache].
         *
         * The [projectIdProvider] should return a string that identifies the current project.
         * It will be anonymized automatically.
         *
         * **IMPORTANT:** This requires user consent. Make sure to check for it using
         * [FusConsent][org.jetbrains.amper.fus.consent.FusConsent].
         */
        fun createAndStart(fusCache: FusCache, projectIdProvider: () -> String?): FusReporter {
            val client = createFusClient(fusCache)

            // No need for scheduled metadata updates for short CLI calls, we can update once per call instead.
            // The overhead should be low, because the download doesn't happen when metadata is up-to-date, so it's
            // just one HTTP HEAD request.
            // TODO once the metadata is bundled, we can make this call asynchronous
            client.updateMetadata()

            // Currently done in an independent coroutine scope created automatically by the client internally.
            // Note: it's ok to send, because we check for consent before calling this function.
            client.scheduleSend()

            return DefaultFusReporter(fusCache, client, projectIdProvider)
        }
    }
}

internal class DefaultFusReporter(
    fusCache: FusCache,
    private val writer: FusClient<LogEvent, *>,
    private val projectIdProvider: () -> String?,
    private val clock: Clock = Clock.System,
) : FusReporter {
    private val sessionId: String = Uuid.random().toString()
    private val systemEventId = AtomicLong()

    // We use the same algorithm as IntelliJ's `EventLogConfiguration.generateMachineId`, and the same fallback as the
    // LSP server's FusEventFactory.
    private val machineId: String = MachineIdProvider.getOrCreateMachineId(fusCache)
    private val bucket: Int = userIdToBucket(machineId)

    override fun log(event: BaseEventId, data: Map<String, Any>) {
        writer.queueEvent(buildEvent(event, data))
    }

    private fun buildEvent(event: BaseEventId, data: Map<String, Any>): LogEvent = LogEvent(
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
        event = LogEventAction(
            id = event.eventId,
            data = data.toMutableMap(),
        ),
        system_data = buildMap {
            putAll(LogEventSystemData(system_event_id = systemEventId.getAndIncrement().toString()).toMap().orEmpty())
        }
    )

    private fun EventLogGroup.toLogEventGroup(): LogEventGroup = LogEventGroup(
        id = id,
        version = version,
        state = type == EventLogGroup.Type.STATE,
    )

   override fun close() {
        writer.close()
    }
}
