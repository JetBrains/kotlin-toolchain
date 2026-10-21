/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.terminal.Terminal
import com.jetbrains.fus.reporting.schema.BaseEventId
import com.jetbrains.fus.reporting.schema.FieldListDescription
import com.jetbrains.fus.reporting.schema.VarargEventId
import org.jetbrains.amper.cli.analytics.CliFusReporter.delegate
import org.jetbrains.amper.cli.analytics.CliFusReporter.enable
import org.jetbrains.amper.cli.terminal.interactiveSelectList
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.fus.consent.ConsentState
import org.jetbrains.amper.fus.consent.FusConsent
import org.jetbrains.amper.fus.reporter.FusReporter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object CliFusReporter : FusReporter {
    private var delegate: FusReporter? = null

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

    private val buffer: ConcurrentLinkedQueue<PendingEvent> = ConcurrentLinkedQueue()

    /**
     * Checks or prompts for consent using the given [terminal], and initializes this reporter depending on whether the
     * consent was given.
     *
     * * if consent was given, the FUS infrastructure is initialized, and all previously buffered events are sent
     * * if consent was rejected, this [FusReporter] becomes a no-op, and all previously buffered events are discarded
     */
    fun setup(userCacheRoot: AmperUserCacheRoot, terminal: Terminal) {
        val consentState = FusConsent.detect { terminal.promptForConsent() }
        when (consentState) {
            ConsentState.Given -> enable(FusCache(userCacheRoot.fusRoot))
            ConsentState.Rejected, null -> cancel()
        }
    }

    /**
     * Initializes the real FUS infrastructure with the given cache location and send all previously reported events.
     * This should only be called after consent has been checked.
     */
    private fun enable(cache: FusCache) {
        cacheSetLock.write {
            delegate = FusReporter.createAndStart(cache)
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
        cacheSetLock.write {
            delegate?.close()
            delegate = null
        }
    }
}

private fun Terminal.promptForConsent(): ConsentState? {
    if (!terminalInfo.interactive) {
        // if the terminal is not interactive, we can't prompt for consent
        return null
    }
    println(Markdown(FusConsent.PROMPT_BANNER_MARKDOWN_1_2))
    val result = interactiveSelectList(
        items = [ConsentState.Given, ConsentState.Rejected],
        nameSelector = {
            when (it) {
                ConsentState.Given -> "Send data"
                ConsentState.Rejected -> "Don't send data"
            }
        },
        preSelectedItem = ConsentState.Rejected,
        filterable = false,
    ) ?: throw PrintMessage("Operation aborted")
    return result
}
