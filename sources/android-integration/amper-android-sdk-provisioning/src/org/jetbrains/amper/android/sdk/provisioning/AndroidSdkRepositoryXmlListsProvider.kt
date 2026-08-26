/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import io.ktor.http.*
import io.opentelemetry.api.OpenTelemetry
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.telemetry.use
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val androidRepositoryUrl: String
    get() {
        val overriddenRepositoryUrl = System.getenv("KOTLIN_TOOLCHAIN_ANDROID_DOWNLOAD_URL_ROOT")
            ?.takeIf { it.isNotBlank() }
        return overriddenRepositoryUrl ?: "https://dl.google.com/"
    }

internal data class AndroidSdkRepository(
    val name: String,
    val baseUrl: Url,
    val packageUrl: Url,
) {
    companion object {
        val Main = AndroidSdkRepository(
            name = "main",
            baseUrl = Url("$androidRepositoryUrl/android/repository"),
            packageUrl = Url("$androidRepositoryUrl/android/repository/repository2-3.xml")
        )
        val SystemImages = AndroidSdkRepository(
            name = "system-images",
            baseUrl = Url("$androidRepositoryUrl/android/repository/sys-img/google_apis"),
            packageUrl = Url("$androidRepositoryUrl/android/repository/sys-img/google_apis/sys-img2-3.xml")
        )
    }
}

/** Downloads and caches the XML package lists published by the Android SDK repositories. */
internal class AndroidSdkRepositoryXmlListsProvider(
    openTelemetry: OpenTelemetry,
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
    private val listValidityPeriod: Duration = 24.hours, // Google returns max-age=86400 for XML lists
) {
    private val tracer = openTelemetry.getTracer("org.jetbrains.amper.android.sdk.provisioning")

    suspend fun getRepositoryXml(repository: AndroidSdkRepository): Path = getOrDownload(
        cacheKey = "android-${repository.name}",
        url = repository.packageUrl,
        repositoryName = repository.name,
    )

    private suspend fun getOrDownload(cacheKey: String, url: Url, repositoryName: String): Path =
        tracer.spanBuilder("Get Android SDK $repositoryName repository XML list").use {
            incrementalCache.execute(
                key = cacheKey,
                inputValues = emptyMap(),
                inputFiles = emptyList(),
            ) {
                val xml = tracer.spanBuilder("Fetch Android SDK $repositoryName repository XML list").use {
                    Downloader.downloadFileToCacheLocation(
                        url = url.toString(),
                        userCacheRoot = userCacheRoot,
                        infoLog = false,
                        // Downloader's cache does not expire by itself; the incremental-cache entry controls freshness.
                        forceRedownload = true,
                    )
                }
                IncrementalCache.ExecutionResult(
                    outputFiles = listOf(xml),
                    expirationTime = Clock.System.now() + listValidityPeriod,
                )
            }.outputFiles.single()
        }
}
