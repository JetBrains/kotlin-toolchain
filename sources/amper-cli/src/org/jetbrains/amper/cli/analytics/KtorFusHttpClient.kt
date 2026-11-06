/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.jetbrains.fus.reporting.FusHttpClient
import com.jetbrains.fus.reporting.HttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.lastModified
import kotlinx.coroutines.runBlocking
import io.ktor.client.statement.HttpResponse as KtorHttpResponse

/**
 * A [FusHttpClient] backed by the given Ktor [httpClient], so that FUS requests go through the same HTTP stack as the
 * rest of the toolchain (user agent, proxy, timeouts, retries).
 *
 * [FusHttpClient] is a synchronous API, which is why the Ktor calls are wrapped in [runBlocking].
 */
internal class KtorFusHttpClient(httpClient: HttpClient) : FusHttpClient {

    // The FUS client inspects the status code of failed requests, so error responses must not throw.
    private val httpClient = httpClient.config { expectSuccess = false }

    override fun post(url: String, data: String): HttpResponse = runBlocking {
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(data)
        }
        response.toFusResponse()
    }

    override fun get(url: String): HttpResponse = runBlocking {
        httpClient.get(url).toFusResponse()
    }

    override fun lastModified(url: String): Long = runBlocking {
        httpClient.head(url).lastModified()?.time ?: 0L
    }
}

private suspend fun KtorHttpResponse.toFusResponse() = HttpResponse(statusCode = status.value, body = bodyAsText())
