/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.appstoreconnect

import java.nio.file.Path

/**
 * App Store Connect API key, also used by `xcodebuild` for authentication without an Xcode account session.
 */
data class AppStoreConnectAuthKey(
    val keyId: String,
    val issuerId: String,
    /**
     * Absolute path to the `.p8` private key file.
     */
    val keyPath: Path,
    /**
     * Base URL of the App Store Connect API.
     */
    val apiUrl: String = AppStoreConnectDefaultApiUrl,
)

/**
 * Base URL of the public App Store Connect API.
 */
const val AppStoreConnectDefaultApiUrl = "https://api.appstoreconnect.apple.com"
