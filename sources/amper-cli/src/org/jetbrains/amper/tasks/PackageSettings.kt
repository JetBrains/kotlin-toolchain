/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.appstoreconnect.AppStoreConnectAuthKey
import java.nio.file.Path

/**
 * Settings that are passed from the command line to the packaging tasks (`package` command).
 */
data class PackageSettings(
    /**
     * Settings specific to the [org.jetbrains.amper.engine.PackageTask.Format.Ipa] format.
     */
    val ipa: IpaPackageSettings = IpaPackageSettings(),
)

/**
 * Settings for packaging iOS apps into `.ipa` files.
 */
class IpaPackageSettings(
    /**
     * Absolute path to a user-provided `ExportOptions.plist`.
     * `null` to generate development export options for automatic signing. Required for manual signing.
     */
    val exportOptionsPlistPath: Path? = null,
    /**
     * Provides the App Store Connect API key for headless signing, or `null` to use the Xcode account session.
     */
    authKeyProvider: () -> AppStoreConnectAuthKey? = { null },
) {
    /** Resolved only when IPA packaging is selected, so unrelated commands do not validate signing credentials. */
    val authKey: AppStoreConnectAuthKey? by lazy(authKeyProvider)
}
