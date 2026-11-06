/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.reporter

import com.jetbrains.fus.reporting.FusClient
import com.jetbrains.fus.reporting.FusHttpClient
import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.schema.BaseEventId
import com.jetbrains.fus.reporting.schema.EventId
import com.jetbrains.fus.reporting.schema.FieldListDescription
import com.jetbrains.fus.reporting.schema.VarargEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.fus.client.createFusClient
import org.jetbrains.amper.fus.client.fusEventDumpFile
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
        fun createAndStart(
            fusCache: FusCache,
            httpClient: FusHttpClient,
            projectIdProvider: () -> String?,
        ): FusReporter {
            val remoteConfigLoaded = CompletableDeferred<Unit>()
            val client = createFusClient(
                fusCache = fusCache,
                httpClient = httpClient,
                onRemoteConfigLoaded = { remoteConfigLoaded.complete(Unit) },
            )

            // No need for scheduled metadata updates for short CLI calls, we can update once per call instead.
            // The overhead should be low, because the download doesn't happen when metadata is up-to-date, so it's
            // just one HTTP HEAD request.
            // Note: despite its name, this call doesn't block, the client updates in its own coroutine scope
            // (this could be changed using enableAsyncEventLogging=false, but that would impact all events as well)
            client.updateMetadata()

            // The URL to send the events to is only known once the remote config has been fetched, so sending before
            // that always fails. This is why we await before scheduling the send so we can be sure to schedule is as
            // soon as possible but after the URL is ready.
            // We must not suspend here and delay the creation of the FUS reporter, because the events should start
            // being recorded and persisted even if the remote config can never be fetched (they will be sent during a
            // subsequent CLI call). This is why we use a longer-lived coroutine scope here, but we make sure it is
            // closed together with the returned FusReporter.
            val scope = CoroutineScope(SupervisorJob())
            scope.launch {
                remoteConfigLoaded.await()
                // Note: it's ok to send unconditionally, because we check for consent before calling this function.
                client.scheduleSend()
            }

            return DefaultFusReporter(fusCache, client, projectIdProvider, scope)
        }
    }
}

internal class DefaultFusReporter(
    fusCache: FusCache,
    private val writer: FusClient<LogEvent, *>,
    projectIdProvider: () -> String?,
    /**
     * The scope in which the start of the event sending is awaited.
     */
    private val sendStartScope: CoroutineScope,
) : FusReporter {
    private val eventFactory = LogEventFactory(
        // We use the same algorithm as IntelliJ's `EventLogConfiguration.generateMachineId`, and the same fallback as
        // the LSP server's FusEventFactory.
        machineIdProvider = { MachineIdProvider.getOrCreateMachineId(fusCache) },
        projectIdProvider = projectIdProvider,
    )

    override fun log(event: BaseEventId, data: Map<String, Any>) {
        val logEvent = eventFactory.create(event, data)
        // We dump the event here and not from the FUS client, because the client silently drops the events that it
        // can't validate, and those are exactly the ones we want to see when debugging or testing new ones.
        fusEventDumpFile?.dumpReportedEvent(logEvent)
        writer.queueEvent(logEvent)
    }

    override fun close() {
        sendStartScope.cancel()
        writer.close()
    }
}

