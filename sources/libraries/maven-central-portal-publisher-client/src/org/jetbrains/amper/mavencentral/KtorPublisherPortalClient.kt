/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.mavencentral

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString

internal class KtorPublisherPortalClient(
    httpClient: HttpClient,
    private val token: UserToken,
) : PublisherPortalClient {

    private val httpClient = httpClient.config {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    override suspend fun uploadBundle(
        zipBundle: Path,
        name: String?,
        publishingType: PublishingType,
    ): DeploymentId {
        // Spec: https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle
        val response = httpClient.submitFormWithBinaryData(
            formData = formData {
                appendFileOctetStream("bundle", zipBundle)
            }
        ) {
            url {
                takeFrom("https://central.sonatype.com/api/v1/publisher/upload")
                parameter("publishingType", publishingType.apiValue)
            }
            // Spec: https://central.sonatype.org/publish/publish-portal-api/#authentication-authorization
            bearerAuth(token.base64)

            // the response is just the deployment ID (UUID)
            accept(ContentType.Text.Plain)
        }
        return DeploymentId(response.bodyAsText().trim())
    }

    private fun FormBuilder.appendFileOctetStream(key: String, file: Path) {
        append(
            key = key,
            filename = file.name,
            contentType = ContentType.Application.OctetStream,
        ) {
            val path = kotlinx.io.files.Path(file.pathString)
            val source = SystemFileSystem.source(path).buffered()
            transferFrom(source)
        }
    }

    override suspend fun getDeploymentStatus(deploymentId: DeploymentId): DeploymentStatus {
        // Spec: https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle
        val response = httpClient.post {
            url {
                takeFrom("https://central.sonatype.com/api/v1/publisher/status")
                parameter("id", deploymentId.uuidValue)
            }
            // Spec: https://central.sonatype.org/publish/publish-portal-api/#authentication-authorization
            bearerAuth(token.base64)
            accept(ContentType.Application.Json)
        }
        return response.body()
    }
}