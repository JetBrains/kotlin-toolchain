/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.klibsio

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

internal class KtorKlibsIoNotifier(
    httpClient: HttpClient,
    private val apiUrl: String,
) : KlibsIoNotifier {

    private val httpClient = httpClient.config {
        install(ContentNegotiation) {
            json()
        }
    }

    override suspend fun notifyArtifactPublished(groupId: String, artifactId: String, version: String): Confirmation {
        val response = httpClient.post(apiUrl) {
            contentType(ContentType.Application.Json)
            setBody(ArtifactCoordinates(groupId = groupId, artifactId = artifactId, version = version))
        }
        return Confirmation(response.bodyAsText())
    }

    @Serializable
    private class ArtifactCoordinates(
        val groupId: String,
        val artifactId: String,
        val version: String,
    )
}
