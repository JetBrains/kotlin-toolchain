/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.github.ajalt.mordant.terminal.Terminal
import com.jetbrains.fus.reporting.schema.BaseEventId
import com.jetbrains.fus.reporting.schema.FieldListDescription
import com.jetbrains.fus.reporting.schema.VarargEventId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.analytics.CliFusReporter.delegate
import org.jetbrains.amper.cli.analytics.CliFusReporter.enable
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
import org.jetbrains.amper.telemetry.useWithoutCoroutines
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.absolute
import kotlin.io.path.invariantSeparatorsPathString

object CliFusReporter : FusReporter {

    private var delegate: FusReporter? = null

    @Volatile
    private var projectId: String? = null

    /**
     * A lock used when setting the cache location.
     *
     * This ensures no events are logged while we are creating the FUS infrastructure, and that we're not creating
     * multiple instances of the infra concurrently.
     *
     * Note: we don't need to synchronize [log] operations between each other, we just need to prevent them
     * from happening concurrently with [enable], hence the read/write lock approach.
     */
    private val cacheSetLock = ReentrantReadWriteLock()

    /**
     * Whether we should buffer events while the [delegate] is not initialized.
     * This should be false if consent has been revoked and we don't want to record events anymore.
     */
    private var shouldBuffer = true

    /**
     * Whether this reporter has been closed (end of main), which means calling [setup] should be a no-op.
     */
    private var closed = false

    private val buffer: ConcurrentLinkedQueue<PendingEvent> = ConcurrentLinkedQueue()

    private val httpClient by lazy { KtorFusHttpClient(amperHttpClient) }

    /**
     * Configures the project so future events are reported with the corresponding project ID.
     */
    fun setProject(projectRoot: Path) {
        // Important: this is automatically anonymized inside the FUS reporting infrastructure.
        // It's not super important to identify the same project across machines at the moment.
        projectId = projectRoot.absolute().invariantSeparatorsPathString
    }

    /**
     * Checks or prompts for consent using the given [terminal], and initializes this reporter depending on whether the
     * consent was given.
     *
     * * if consent was given, the FUS infrastructure is initialized, and all previously buffered events are sent
     * * if consent was rejected, this [FusReporter] becomes a no-op, and all previously buffered events are discarded
     *
     * In both cases, the consent state itself is reported (at most once per day) using an event without identifiers.
     */
    suspend fun setup(
        userCacheRoot: AmperUserCacheRoot,
        terminal: Terminal,
        consentState: ConsentState?,
        fusSchedulersParentScope: CoroutineScope,
    ) {
        if (closed) {
            return
        }
        val cache = FusCache(userCacheRoot.fusRoot)
        when (consentState) {
            ConsentState.Accepted -> enable(
                cache = cache,
                fusSchedulersParentScope = fusSchedulersParentScope,
                sendOnClose = when (FusEnvironmentDetection.callerType) {
                    FusCommandCallerType.AIAgent,
                    FusCommandCallerType.Ide,
                    FusCommandCallerType.Terminal -> false // it's ok to send events in the next call, let's save time
                    FusCommandCallerType.CI -> true // we might not have another chance
                }
            )
            ConsentState.Rejected, null -> cancel()
        }
        reportConsentStateIfDue(cache, consentState, terminal.terminalInfo.interactive)
    }

    /**
     * Initializes the real FUS infrastructure with the given cache location and send all previously reported events.
     * This should only be called after consent has been checked.
     */
    private fun enable(cache: FusCache, fusSchedulersParentScope: CoroutineScope, sendOnClose: Boolean) {
        cacheSetLock.write {
            if (closed) {
                return
            }
            delegate = FusReporter.createAndStart(
                fusCache = cache,
                httpClient = httpClient,
                projectIdProvider = { projectId },
                fusSchedulersParentScope = fusSchedulersParentScope,
                sendOnClose = sendOnClose,
            )
            while (buffer.isNotEmpty()) {
                val event = buffer.poll()
                delegate?.log(event.id, event.data)
            }
        }
    }

    /**
     * Cancels the buffering of events. From that point, [log] becomes a no-op.
     * This should be called if consent has been revoked.
     */
    private fun cancel() {
        cacheSetLock.write {
            delegate = null
            shouldBuffer = false
            buffer.clear()
        }
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
                FusReporter.createAndStart(
                    fusCache = cache,
                    httpClient = httpClient,
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

    override fun <D : FieldListDescription> log(event: VarargEventId<D>, buildData: D.() -> Unit) {
        if (delegate == null && !shouldBuffer) {
            return // shortcut if we know we canceled data collection, so we don't call `buildData`
        }
        super.log(event, buildData)
    }

    override fun log(event: BaseEventId, data: Map<String, Any>) {
        if (delegate == null && !shouldBuffer) {
            return // we canceled collection, no need to go further
        }
        cacheSetLock.read {
            delegate?.log(event, data) ?: run {
                if (shouldBuffer) {
                    buffer.add(PendingEvent(event, data))
                }
            }
        }
    }

    private class PendingEvent(val id: BaseEventId, val data: Map<String, Any>)

    override fun close() {
        spanBuilder("Closing FUS client").useWithoutCoroutines {
            cacheSetLock.write {
                delegate?.close()
                delegate = null
                closed = true
            }
        }
    }
}

