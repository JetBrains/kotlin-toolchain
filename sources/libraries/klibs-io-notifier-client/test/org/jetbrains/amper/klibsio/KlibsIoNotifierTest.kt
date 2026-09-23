/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.klibsio

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlibsIoNotifierTest {

    @Test
    fun `posts the coordinates as an escaped JSON body to the configured URL`() = runTest {
        lateinit var request: HttpRequestData
        val engine = MockEngine { capturedRequest ->
            request = capturedRequest
            respond(content = ACK_BODY, status = HttpStatusCode.OK)
        }

        KlibsIoNotifier(clientWith(engine), API_URL)
            .notifyArtifactPublished("com.example", "test-artifact", "1.0.0+\"build\"")

        assertEquals(HttpMethod.Post, request.method)
        assertEquals(API_URL, request.url.toString())
        assertEquals(
            """{"groupId":"com.example","artifactId":"test-artifact","version":"1.0.0+\"build\""}""",
            (request.body as TextContent).text,
        )
    }

    @Test
    fun `returns the response body on a 2xx response`() = runTest {
        val engine = MockEngine { respond(content = ACK_BODY, status = HttpStatusCode.OK) }

        val result = KlibsIoNotifier(clientWith(engine), API_URL)
            .notifyArtifactPublished("com.example", "test-artifact", "1.2.3")

        assertEquals(Confirmation(ACK_BODY), result)
    }

    @Test
    fun `throws with the status and body available on a client error response`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest, content = "already indexed") }

        val exception = assertFailsWith<ClientRequestException> {
            KlibsIoNotifier(clientWith(engine), API_URL)
                .notifyArtifactPublished("com.example", "test-artifact", "1.2.3")
        }

        assertEquals(HttpStatusCode.BadRequest, exception.response.status)
        assertEquals("already indexed", exception.response.bodyAsText())
    }

    @Test
    fun `throws on a server error response`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        val exception = assertFailsWith<ServerResponseException> {
            KlibsIoNotifier(clientWith(engine), API_URL)
                .notifyArtifactPublished("com.example", "test-artifact", "1.2.3")
        }

        assertEquals(HttpStatusCode.InternalServerError, exception.response.status)
    }

    @Test
    fun `propagates network failures`() = runTest {
        val engine = MockEngine { throw IOException("klibs.io is unreachable") }

        assertFailsWith<IOException> {
            KlibsIoNotifier(clientWith(engine), API_URL)
                .notifyArtifactPublished("com.example", "test-artifact", "1.2.3")
        }
    }

    private fun clientWith(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = true
    }

    private companion object {
        const val API_URL = "https://klibs.example/notify/artifacts"
        const val ACK_BODY = "The indexing request has been successfully added to the klibs.io queue."
    }
}
