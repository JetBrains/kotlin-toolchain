/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.jetbrains.fus.reporting.FusHttpClient
import com.jetbrains.fus.reporting.HttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.lastModified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.statement.HttpResponse as KtorHttpResponse

/**
 * A [FusHttpClient] backed by the given Ktor [httpClient], so that FUS requests go through the same HTTP stack as the
 * rest of the toolchain (user agent, proxy).
 *
 * FUS is not critical, so requests use short timeouts and are not retried, whatever the configuration of the given
 * [httpClient].
 *
 * [FusHttpClient] is a synchronous API, which is why the Ktor calls are wrapped in [runBlocking]. The calls are tied to
 * the lifetime of this client: [close] cancels the requests in progress (they throw a [CancellationException]), and
 * makes subsequent requests fail immediately.
 */
internal class KtorFusHttpClient(httpClient: HttpClient) : FusHttpClient {

    private val httpClient = httpClient.config {
        // The FUS client inspects the status code of failed requests, so error responses must not throw.
        expectSuccess = false

        // We limit retries because FUS is not critical, and we want to limit the impact of a misbehaved FUS server for
        // critical moments like shutdown.
        install(HttpRequestRetry) {
            noRetry()
        }
        // We reduce timeouts because fetching configs and sending events should be fast, and we want to limit the
        // impact of a misbehaved FUS server.
        install(HttpTimeout) {
            connectTimeoutMillis = 3.seconds.inWholeMilliseconds
            requestTimeoutMillis = 5.seconds.inWholeMilliseconds
        }
    }

    /**
     * The parent job of all requests, canceled when this client is closed.
     */
    private val requestsJob = SupervisorJob()

    override fun post(url: String, data: String): HttpResponse = runBlockingCancellable {
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(data)
        }
        response.toFusResponse()
    }

    override fun get(url: String): HttpResponse = runBlockingCancellable {
        httpClient.get(url).toFusResponse()
    }

    override fun lastModified(url: String): Long = runBlockingCancellable {
        httpClient.head(url).lastModified()?.time ?: 0L
    }

    /**
     * Runs the given request [block], blocking the current thread, as a child of [requestsJob] so it can be canceled.
     */
    private fun <T> runBlockingCancellable(block: suspend CoroutineScope.() -> T): T = runBlocking(requestsJob, block)

    override suspend fun close() {
        requestsJob.cancel()
    }
}

private suspend fun KtorHttpResponse.toFusResponse() = HttpResponse(statusCode = status.value, body = bodyAsText())
