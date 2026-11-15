/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val json = Json { ignoreUnknownKeys = true }

private const val PRODUCT_CODE = "KTC"
private const val RECORDER_CODE = "KTC"
private const val REMOTE_CONFIG_URL = "https://resources.jetbrains.com/storage/fus/config/v4/$PRODUCT_CODE/$PRODUCT_CODE.json"

// Has to match the bundledFileStorage config in createFusClient.kt
private const val METADATA_RES_ROOT_DIR = "fus-metadata"

/**
 * Downloads the FUS metadata and validation dictionaries into [outputDir] so they can be bundled in the
 * distribution, sparing the first CLI run the initial download.
 *
 * Each file is stored next to a `.meta` sidecar containing its last-modified timestamp, which is the same layout the
 * FUS SDK uses for its on-disk cache. Files are only rewritten when their content changes, so repeated builds don't
 * invalidate the task outputs.
 */
@TaskAction
fun fetchFusMetadata(@Output outputDir: Path) {
    val outputFusResourcesDir = outputDir / METADATA_RES_ROOT_DIR
    runBlocking {
        HttpClient {
            expectSuccess = true
            install(ContentNegotiation) {
                json(json)
            }
        }.use { httpClient ->
            val endpoints = httpClient.get(REMOTE_CONFIG_URL).body<RemoteConfig>().versions.singleOrNull()?.endpoints
                ?: error("The analytics server now responds multiple versions, we need to rethink metadata bundling")

            // The FUS client requests the unversioned metadata (baselineVersion = 0), which lives at '<endpoint>/<product code>.json'.
            httpClient.download(
                url = "${endpoints.metadata.trimEnd('/')}/$PRODUCT_CODE.json",
                target = outputFusResourcesDir / "events-scheme.json",
            )

            val dictionaryUrlPrefix = "${endpoints.dictionary.trimEnd('/')}/$RECORDER_CODE"
            val dictionaryListContent = httpClient.download(
                url = "$dictionaryUrlPrefix/dictionaries.json",
                target = outputFusResourcesDir / "dictionaries/dictionaries.json",
            )
            for (dictionaryName in json.decodeFromString<RemoteDictionaryList>(dictionaryListContent).dictionaries) {
                httpClient.download(
                    url = "$dictionaryUrlPrefix/$dictionaryName",
                    target = outputFusResourcesDir / "dictionaries" / dictionaryName
                )
            }
        }
    }
}

private suspend fun HttpClient.download(url: String, target: Path): String {
    val response = get(url)
    val content = response.bodyAsText()
    writeIfChanged(content, target)
    writeIfChanged((response.lastModified()?.time ?: 0L).toString(), target.metaFile)
    return content
}

private val Path.metaFile: Path
    get() = resolveSibling("$fileName.meta")

private fun writeIfChanged(content: String, target: Path) {
    if (!target.exists() || target.readText() != content) {
        target.createParentDirectories().writeText(content)
    }
}

@Serializable
private data class RemoteConfig(val versions: List<VersionConfig>)

@Serializable
private class VersionConfig(val endpoints: EndpointsConfig)

@Serializable
private class EndpointsConfig(val metadata: String, val dictionary: String)

@Serializable
private class RemoteDictionaryList(val dictionaries: List<String> = emptyList())
