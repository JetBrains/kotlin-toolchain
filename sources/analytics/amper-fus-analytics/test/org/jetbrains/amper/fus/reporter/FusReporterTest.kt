/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.reporter

import com.jetbrains.fus.reporting.FusHttpClient
import com.jetbrains.fus.reporting.HttpResponse
import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.serialization.FusKotlinSerializer
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.fus.schema.FusEvents
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class FusReporterTest {

    @TempDir
    lateinit var tempDir: Path

    private val fusCache by lazy { FusCache(tempDir / "fus") }

    @Test
    fun `reported events are persisted on close with bundled metadata`() = runTestWithMdc {
        val reporter = FusReporter.createAndStart(
            fusCache = fusCache,
            httpClient = AllNotFoundFusHttpClient(),
            projectIdProvider = { null },
            sendOnClose = false,
            fusSchedulersParentScope = backgroundScope,
        )
        reporter.log(FusEvents.CommandInvoked, emptyMap())
        reporter.close()

        assertEquals(["ktc.command.invoked"], persistedEventIds())
    }

    private fun persistedEventIds(): List<String> {
        val queuesDir = fusCache.eventQueuesDir
        if (!queuesDir.exists()) {
            return []
        }
        val serializer = FusKotlinSerializer()
        return queuesDir.walk()
            .filter { it.isRegularFile() && it.name.endsWith(".log") }
            .flatMap { it.readLines() }
            .filter { it.isNotBlank() }
            .map { serializer.fromJson(it, LogEvent::class).event.id }
            .toList()
    }
}

/**
 * A [FusHttpClient] failing all requests (fetching metadata and sending events).
 */
private class AllNotFoundFusHttpClient : FusHttpClient {

    override fun post(url: String, data: String): HttpResponse = HttpResponse(statusCode = 503, body = null)
    override fun get(url: String): HttpResponse = HttpResponse(statusCode = 404, body = null)
    override fun lastModified(url: String): Long = Long.MAX_VALUE
}
