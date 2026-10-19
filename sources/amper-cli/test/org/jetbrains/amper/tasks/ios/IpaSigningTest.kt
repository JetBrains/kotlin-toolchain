/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.tasks.IpaPackageSettings
import org.jetbrains.amper.util.BuildType
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class IpaSigningTest {
    @Test
    fun `manual signing requires explicit export options even with a profile specifier`() {
        val error = assertFailsWith<UserReadableError> {
            resolveSigning(
                resolver("Manual", "PROVISIONING_PROFILE_SPECIFIER" to "profile"), "app",
                BuildType.Release, IpaPackageSettings()
            )
        }
        assertContains(error.message, "--ipa-export-options-plist-path")
    }

    @Test
    fun `manual signing with legacy profile UUID and explicit export options is accepted`() {
        resolveSigning(
            resolver("Manual", "PROVISIONING_PROFILE" to "profile-uuid"), "app", BuildType.Release,
            IpaPackageSettings(exportOptionsPlistPath = Path("ExportOptions.plist"))
        )
    }

    @Test
    fun `manual signing with explicit export options leaves profile validation to Xcode`() {
        resolveSigning(
            resolver("Manual"), "app", BuildType.Release,
            IpaPackageSettings(exportOptionsPlistPath = Path("ExportOptions.plist"))
        )
    }

    @Test
    fun `automatic signing does not require explicit export options`() {
        resolveSigning(resolver("Automatic"), "app", BuildType.Release, IpaPackageSettings())
    }

    private fun resolver(style: String, vararg settings: Pair<String, String>) =
        object : XcodeBuildSettingsResolution.Resolver {
            private val values = mapOf(
                "DEVELOPMENT_TEAM" to "TEAM",
                "CODE_SIGN_STYLE" to style,
                "PRODUCT_BUNDLE_IDENTIFIER" to "org.example.app",
                *settings,
            )

            override fun getSetting(key: String): String? = values[key]
            override fun getRequiredSetting(key: String): String = values.getValue(key)
        }
}