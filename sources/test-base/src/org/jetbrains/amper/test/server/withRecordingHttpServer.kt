/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.server

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

data class RecordedRequest(
    val method: String,
    val path: String,
    val body: String,
)

suspend fun withRecordingHttpServer(
    responseStatus: Int = 200,
    responseBody: String = "",
    block: suspend (baseUrl: String) -> Unit,
): List<RecordedRequest> {
    val requests = mutableListOf<RecordedRequest>()
    val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
    try {
        httpServer.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }.decodeToString()
            requests.add(RecordedRequest(exchange.requestMethod, exchange.requestURI.path, body))

            val responseBytes = responseBody.encodeToByteArray()
            exchange.sendResponseHeaders(responseStatus, if (responseBytes.isEmpty()) -1 else responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        httpServer.start()

        block("http://127.0.0.1:${httpServer.address.port}")
    } finally {
        httpServer.stop(0)
    }
    return requests
}
