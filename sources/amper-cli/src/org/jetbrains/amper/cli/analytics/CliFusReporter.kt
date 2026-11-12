/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.github.ajalt.mordant.terminal.Terminal
import com.jetbrains.fus.reporting.schema.BaseEventId
import com.jetbrains.fus.reporting.schema.FieldListDescription
import com.jetbrains.fus.reporting.schema.VarargEventId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.analytics.CliFusReporter.cancel
import org.jetbrains.amper.cli.analytics.CliFusReporter.channel
import org.jetbrains.amper.cli.analytics.CliFusReporter.delegate
import org.jetbrains.amper.cli.analytics.CliFusReporter.enable
import org.jetbrains.amper.concurrency.use
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.amperHttpClient
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.fus.consent.ConsentState
import org.jetbrains.amper.fus.consent.ConsentStateReportThrottle
import org.jetbrains.amper.fus.reporter.FusReporter
import org.jetbrains.amper.fus.schema.AnonymousOsFamily
import org.jetbrains.amper.fus.schema.FusCommandCallerType
import org.jetbrains.amper.fus.schema.FusConsentState
import org.jetbrains.amper.fus.schema.FusEvents
import org.jetbrains.amper.fus.schema.trimKtcVersionForFus
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.Duration.Companion.milliseconds

object CliFusReporter : FusReporter {

    private val logger = LoggerFactory.getLogger(CliFusReporter::class.java)

    private var delegate: FusReporter? = null

    /**
     * The queue of the events waiting to be passed to the [delegate], preserving their order.
     *
     * This also includes [ProjectIdChange], because we want this to be ordered in a deterministic way with respect to
     * reported events, so we can guarantee which have and which don't have a project ID.
     *
     * Until [enable] starts the consumer coroutine, this is where the logged events are buffered. It's closed when
     * event collection is canceled ([cancel]) or when this reporter is closed ([close]), which makes future [log]
     * calls no-ops.
     */
    private val channel = Channel<OrderedEvent>(Channel.UNLIMITED)

    /**
     * The coroutine in which [setupAsync] runs, or `null` if [setupAsync] was never called.
     *
     * [close] joins it to make sure the [delegate] was fully created (and its consumer started) before closing it.
     * It's assigned before the first suspension point of [setupAsync], so an undispatched launch makes it visible to
     * [close] right away.
     */
    @Volatile
    private var setupJob: Job? = null

    /**
     * The coroutine that consumes the [channel] and passes the events to the [delegate], preserving their order.
     * It's started by [enable] and joined by [close].
     */
    private var eventReportingJob: Job? = null

    /**
     * Configures the project so future events are reported with the corresponding project ID.
     */
    fun setProject(projectRoot: Path) {
        // Important: this is automatically anonymized inside the FUS reporting infrastructure.
        // It's not super important to identify the same project across machines at the moment.
        channel.trySend(ProjectIdChange(projectRoot.absolute().invariantSeparatorsPathString))
    }

    /**
     * Initializes this reporter depending on whether the consent was given.
     *
     * * if consent was given, the FUS infrastructure is initialized, and starts consuming the buffered events
     * * if consent was rejected, this [FusReporter] becomes a no-op, and all previously buffered events are discarded
     *
     * In both cases, the consent state itself is reported (at most once per day) using an event without identifiers.
     */
    fun setupAsync(
        userCacheRoot: AmperUserCacheRoot,
        terminal: Terminal,
        consentState: ConsentState?,
        fusCoroutineScope: CoroutineScope,
    ) {
        setupJob = fusCoroutineScope.launch {
            spanBuilder("Setup FUS reporting").use {
                val cache = FusCache(userCacheRoot.fusRoot)
                when (consentState) {
                    ConsentState.Accepted -> enable(
                        cache = cache,
                        fusCoroutineScope = fusCoroutineScope,
                        sendOnClose = when (FusEnvironmentDetection.callerType) {
                            FusCommandCallerType.AIAgent,
                            FusCommandCallerType.Ide,
                            FusCommandCallerType.Terminal
                                -> false // it's ok to send events in the next call, let's save time
                            FusCommandCallerType.CI -> true // we might not have another chance
                        }
                    )
                    ConsentState.Rejected, null -> cancel()
                }
                reportConsentStateIfDue(cache, consentState, terminal.terminalInfo.interactive)
            }
        }
    }

    /**
     * Initializes the real FUS infrastructure with the given cache location and starts consuming the buffered events.
     * This should only be called after consent has been checked.
     */
    private fun enable(cache: FusCache, fusCoroutineScope: CoroutineScope, sendOnClose: Boolean) {
        var projectId: String? = null
        val reporter = FusReporter.createAndStart(
            fusCache = cache,
            httpClient = KtorFusHttpClient(amperHttpClient),
            projectIdProvider = { projectId },
            fusSchedulersParentScope = fusCoroutineScope,
            sendOnClose = sendOnClose,
        )
        delegate = reporter
        eventReportingJob = fusCoroutineScope.launch {
            for (event in channel) {
                when (event) {
                    is ProjectIdChange -> projectId = event.newProjectId
                    is FusEventReported -> try {
                        // This is synchronous (async logging is disabled on the FUS client).
                        // When it completes, the event is at least validated and persisted on disk.
                        reporter.log(event.id, event.data)
                    } catch (e: Exception) {
                        // We don't let one bad event kill the consumer, which would drop all the subsequent events.
                        logger.debug("ERROR: cannot log FUS event {}", event.id, e)
                    }
                }
            }
        }
    }

    /**
     * Cancels the buffering of events. From that point, [log] becomes a no-op.
     * This should be called if consent has been revoked.
     */
    private fun cancel() {
        channel.close()
    }

    /**
     * Reports the [consentState] if it wasn't reported in the last 24h.
     *
     * The consent state event contains no identifier, so it is sent even if consent was not given.
     * In that case, a temporary reporter is used to send only this event.
     */
    private suspend fun reportConsentStateIfDue(cache: FusCache, consentState: ConsentState?, interactive: Boolean) {
        val throttle = ConsentStateReportThrottle(cache)
        if (!throttle.isReportDue()) {
            return
        }
        if (consentState == ConsentState.Accepted) {
            logConsentState(reporter = this, consentState, interactive)
        } else {
            coroutineScope {
                // we still start a temporary reporter just to report this one negative consent state
                FusReporter.createAndStart(
                    fusCache = cache,
                    httpClient = KtorFusHttpClient(amperHttpClient),
                    projectIdProvider = { null },
                    fusSchedulersParentScope = this,
                    sendOnClose = true, // we don't want to rely on the persistent queue here, and the overhead is low
                ).use { reporter ->
                    logConsentState(reporter, consentState, interactive)
                }
            }
        }
        throttle.markReported()
    }

    private fun logConsentState(reporter: FusReporter, consent: ConsentState?, isInteractive: Boolean) {
        reporter.log(FusEvents.ConsentState) {
            consentState = when (consent) {
                ConsentState.Accepted -> FusConsentState.Accepted
                ConsentState.Rejected -> FusConsentState.Rejected
                null -> FusConsentState.NotAsked
            }
            currentKtcVersion = AmperBuild.mavenVersion.trimKtcVersionForFus()
            osFamily = when (SystemInfo.CurrentHost.family) {
                OsFamily.Windows -> AnonymousOsFamily.Windows
                OsFamily.MacOs -> AnonymousOsFamily.MacOS
                OsFamily.FreeBSD,
                OsFamily.Linux -> AnonymousOsFamily.UnixLike
                OsFamily.Solaris -> null // will be removed soon
            }
            interactive = isInteractive
            ide = FusEnvironmentDetection.ide
            callerType = FusEnvironmentDetection.callerType
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun <D : FieldListDescription> log(event: VarargEventId<D>, buildData: D.() -> Unit) {
        if (channel.isClosedForSend) {
            return // shortcut if we know we won't collect this event, so we don't call `buildData`
        }
        super.log(event, buildData)
    }

    override fun log(event: BaseEventId, data: Map<String, Any>) {
        // The result is ignored: a failure only means the channel is closed, i.e. the event is dropped on purpose.
        channel.trySend(FusEventReported(event, data))
    }

    override suspend fun close() {
        spanBuilder("Closing FUS client").use {
            // We need to make sure we await this, otherwise a delegate FusClient could be initialized and never closed.
            // This must happen before closing the channel, because the setup itself logs the consent state event.
            setupJob?.let { job ->
                spanBuilder("Awaiting already started FUS setup").use {
                    job.join()
                }
            }

            channel.close()

            withTimeoutOrNull(500.milliseconds) {
                eventReportingJob?.let { job ->
                    spanBuilder("Validating & persisting pending FUS events").use {
                        job.join()
                    }
                }
            }

            delegate?.close()
            delegate = null
        }
    }
}

private sealed interface OrderedEvent

private class FusEventReported(val id: BaseEventId, val data: Map<String, Any>) : OrderedEvent

private class ProjectIdChange(val newProjectId: String) : OrderedEvent
