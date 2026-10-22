/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import com.github.ajalt.clikt.core.UsageError
import org.jetbrains.amper.appstoreconnect.AppStoreConnectAuthKey
import org.jetbrains.amper.appstoreconnect.AppStoreConnectDefaultApiUrl
import java.nio.file.InvalidPathException
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile

internal fun ipaAuthKey(): AppStoreConnectAuthKey? {
    val keyId = System.getenv(KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV)
    val issuerId = System.getenv(KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV)
    val keyPath = System.getenv(KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV)
    if (keyId == null && issuerId == null && keyPath == null) {
        return null
    }
    if (keyId == null || issuerId == null || keyPath == null) {
        val provided = mutableListOf<String>()
        val missing = mutableListOf<String>()
        (if (keyId != null) provided else missing) += KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV
        (if (issuerId != null) provided else missing) += KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV
        (if (keyPath != null) provided else missing) += KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV
        throw UsageError(
            """
                The App Store Connect authentication environment variables must be provided together, or not at all.
                Provided: ${provided.joinToString()}.
                Missing: ${missing.joinToString()}.
                """.trimIndent()
        )
    }
    val path = try {
        Path(keyPath).absolute()
    } catch (_: InvalidPathException) {
        throw UsageError("$KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV is set to an invalid path: $keyPath")
    }
    if (!path.exists()) {
        throw UsageError("$KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV ($path) doesn't exist.")
    }
    if (!path.isRegularFile() || !path.isReadable()) {
        throw UsageError("$KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV ($path) is not a readable file.")
    }
    return AppStoreConnectAuthKey(keyId = keyId, issuerId = issuerId, keyPath = path, apiUrl = ascApiUrl())
}

/**
 * The App Store Connect API base URL, which can be overridden with [KOTLIN_TOOLCHAIN_ASC_API_URL_ENV].
 */
internal fun ascApiUrl(): String =
    System.getenv(KOTLIN_TOOLCHAIN_ASC_API_URL_ENV)?.takeIf { it.isNotBlank() } ?: AppStoreConnectDefaultApiUrl

internal const val KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV = "KOTLIN_TOOLCHAIN_ASC_KEY_ID"
internal const val KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV = "KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID"
internal const val KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV = "KOTLIN_TOOLCHAIN_ASC_KEY_PATH"
internal const val KOTLIN_TOOLCHAIN_ASC_API_URL_ENV = "KOTLIN_TOOLCHAIN_ASC_API_URL"