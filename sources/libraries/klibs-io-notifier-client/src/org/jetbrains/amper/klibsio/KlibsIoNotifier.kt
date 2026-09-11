/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.klibsio

import io.ktor.client.HttpClient

interface KlibsIoNotifier {

    /**
     * Sends an HTTP POST notification to klibs.io with Maven coordinates,
     * so the library can be later indexed on the website.
     */
    suspend fun notifyArtifactPublished(groupId: String, artifactId: String, version: String)

    companion object {
        operator fun invoke(httpClient: HttpClient, apiUrl: String): KlibsIoNotifier =
            KtorKlibsIoNotifier(httpClient = httpClient, apiUrl = apiUrl)
    }
}
