/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.reporter

import com.jetbrains.fus.reporting.FusClient
import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.schema.BaseEventId
import com.jetbrains.fus.reporting.schema.EventId
import com.jetbrains.fus.reporting.schema.FieldListDescription
import com.jetbrains.fus.reporting.schema.VarargEventId
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.fus.client.createFusClient
import org.jetbrains.amper.fus.userid.MachineIdProvider
import org.jetbrains.amper.fus.userid.isInternalUserForFUS
import org.slf4j.LoggerFactory

/**
 * Allows logging feature usage statistics.
 */
interface FusReporter : AutoCloseable {

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
        try {
            log(event, event.buildEventData(buildData))
        } catch (e: Exception) {
            if (isInternalUserForFUS) {
                logger.error("Failed to log event $event", e)
            } else {
                logger.debug("ERROR: cannot log event {}", event, e)
            }
        }
    }

    /**
     * Closes the reporter and flushes all pending events.
     */
    override fun close()

    companion object {

        private val logger = LoggerFactory.getLogger(FusReporter::class.java)

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
    projectIdProvider: () -> String?,
) : FusReporter {
    private val eventFactory = LogEventFactory(
        // We use the same algorithm as IntelliJ's `EventLogConfiguration.generateMachineId`, and the same fallback as
        // the LSP server's FusEventFactory.
        machineIdProvider = { MachineIdProvider.getOrCreateMachineId(fusCache) },
        projectIdProvider = projectIdProvider,
    )

    override fun log(event: BaseEventId, data: Map<String, Any>) {
        writer.queueEvent(eventFactory.create(event, data))
    }

    override fun close() {
        writer.close()
    }
}

