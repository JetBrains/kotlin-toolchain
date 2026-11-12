/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.jetbrains.fus.reporting.HttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class KtorFusHttpClientTest {

    @Test
    fun `post sends the data as JSON to the given url and returns the response`() {
        lateinit var request: HttpRequestData
        val engine = MockEngine { capturedRequest ->
            request = capturedRequest
            respond(content = "accepted", status = HttpStatusCode.OK)
        }

        val response = KtorFusHttpClient(clientWith(engine)).post(SEND_URL, REPORT_JSON)

        assertEquals(HttpMethod.Post, request.method)
        assertEquals(SEND_URL, request.url.toString())
        val body = request.body as TextContent
        assertEquals(REPORT_JSON, body.text)
        assertEquals(ContentType.Application.Json, body.contentType.withoutParameters())
        assertEquals(HttpResponse(statusCode = 200, body = "accepted"), response)
    }

    @Test
    fun `post returns the status code of error responses instead of throwing`() {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable, content = "try again later") }

        val response = KtorFusHttpClient(clientWith(engine)).post(SEND_URL, REPORT_JSON)

        assertEquals(HttpResponse(statusCode = 503, body = "try again later"), response)
    }

    @Test
    fun `get returns the status code and body of the response`() {
        lateinit var request: HttpRequestData
        val engine = MockEngine { capturedRequest ->
            request = capturedRequest
            respond(content = METADATA_JSON, status = HttpStatusCode.OK)
        }

        val response = KtorFusHttpClient(clientWith(engine)).get(METADATA_URL)

        assertEquals(HttpMethod.Get, request.method)
        assertEquals(METADATA_URL, request.url.toString())
        assertEquals(HttpResponse(statusCode = 200, body = METADATA_JSON), response)
    }

    @Test
    fun `get returns the status code of error responses instead of throwing`() {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, content = "no such metadata") }

        val response = KtorFusHttpClient(clientWith(engine)).get(METADATA_URL)

        assertEquals(HttpResponse(statusCode = 404, body = "no such metadata"), response)
    }

    @Test
    fun `lastModified issues a HEAD request and returns the Last-Modified timestamp`() {
        lateinit var request: HttpRequestData
        val engine = MockEngine { capturedRequest ->
            request = capturedRequest
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.LastModified, "Wed, 21 Oct 2015 07:28:00 GMT"),
            )
        }

        val lastModified = KtorFusHttpClient(clientWith(engine)).lastModified(METADATA_URL)

        assertEquals(HttpMethod.Head, request.method)
        assertEquals(METADATA_URL, request.url.toString())
        assertEquals(Instant.parse("2015-10-21T07:28:00Z").toEpochMilli(), lastModified)
    }

    @Test
    fun `lastModified returns 0 when the response has no Last-Modified header`() {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.OK) }

        val lastModified = KtorFusHttpClient(clientWith(engine)).lastModified(METADATA_URL)

        assertEquals(0L, lastModified)
    }

    @Test
    fun `requests in progress are canceled when the client is closed`() {
        val requestStarted = CompletableDeferred<Unit>()
        val engine = MockEngine {
            requestStarted.complete(Unit)
            awaitCancellation()
        }
        val fusHttpClient = KtorFusHttpClient(clientWith(engine))

        // FusHttpClient is a blocking API, so we call it from another thread
        val request = CompletableFuture.supplyAsync { runCatching { fusHttpClient.get(METADATA_URL) } }
        runBlocking {
            requestStarted.await()
            fusHttpClient.close()
        }

        val result = request.get(10, TimeUnit.SECONDS)
        assertIs<CancellationException>(result.exceptionOrNull(), "the request should be canceled")
    }

    @Test
    fun `no request is sent once the client is closed`(): Unit = runBlocking {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond(content = METADATA_JSON, status = HttpStatusCode.OK)
        }
        val fusHttpClient = KtorFusHttpClient(clientWith(engine))
        fusHttpClient.close()

        assertFailsWith<CancellationException> { fusHttpClient.get(METADATA_URL) }
        assertFailsWith<CancellationException> { fusHttpClient.post(SEND_URL, REPORT_JSON) }
        assertFailsWith<CancellationException> { fusHttpClient.lastModified(METADATA_URL) }
        assertEquals(0, requestCount)
    }

    @Test
    fun `requests time out quickly and are not retried, regardless of the given client configuration`() {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            delay(1.minutes)
            respond(content = METADATA_JSON, status = HttpStatusCode.OK)
        }
        // like the shared client of the Kotlin Toolchain
        val patientClient = HttpClient(engine) {
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                retryOnException(maxRetries = 3, retryOnTimeout = true)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 2.minutes.inWholeMilliseconds
            }
        }

        val duration = measureTime {
            assertFailsWith<HttpRequestTimeoutException> {
                KtorFusHttpClient(patientClient).get(METADATA_URL)
            }
        }

        assertEquals(1, requestCount, "the request should not be retried")
        assertTrue(duration < 20.seconds, "the request should time out quickly, but took $duration")
    }

    /**
     * Creates a client with [expectSuccess][io.ktor.client.HttpClientConfig.expectSuccess] enabled, like the shared
     * client of the Kotlin Toolchain, to check that error responses are still turned into regular [HttpResponse]s.
     */
    private fun clientWith(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = true
    }

    private companion object {
        const val SEND_URL = "https://fus.example/uploadEvents"
        const val METADATA_URL = "https://fus.example/metadata/ktc/events-scheme.json"
        const val REPORT_JSON = """{"events":[]}"""
        const val METADATA_JSON = """{"groups":[]}"""
    }
}
