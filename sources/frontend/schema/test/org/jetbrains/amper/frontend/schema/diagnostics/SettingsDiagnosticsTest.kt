/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test

class SettingsDiagnosticsTest : FrontendTestCaseBase(Path("testResources") / "diagnostics") {
    @Test
    fun `compose version when compose disabled`() {
        diagnosticsTest("compose-version-disabled")
    }

    @Test
    fun `serialization version when serialization disabled`() {
        diagnosticsTest("serialization-version-disabled")
    }

    @Test
    fun `no redundancy for kotlin serialization format`() {
        diagnosticsTest("setting-no-redundancy-kotlin-serialization")
    }

    @Test
    fun `setting overrides same value`() {
        diagnosticsTest("setting-overrides-same-value")
    }

    @Test
    fun `android settings in jvm lib`() {
        diagnosticsTest("android-settings-in-jvm-lib")
    }

    @Test
    fun `setting main class with lib`() {
        diagnosticsTest("setting-main-class-with-lib")
    }

    @Test
    fun `settings context specificity`() {
        diagnosticsTest("settings-context-specificity")
    }

    @Test
    fun `settings no modifiers allowed`() {
        diagnosticsTest("settings-no-modifiers-allowed")
    }

    @Test
    fun `settings context specificity valid with aliases`() {
        diagnosticsTest("settings-context-specificity-valid-with-aliases")
    }

    @Test
    fun `invalid kotlin compiler version`() {
        diagnosticsTest("invalid-kotlin-compiler-version")
    }

    @Test
    fun `kotlin compiler version too low`() {
        diagnosticsTest("kotlin-compiler-version-too-low")
    }

    @Test
    fun `jdk version too low`() {
        diagnosticsTest("jdk-version-too-low")
    }

    @Test
    fun `compose material3 with unknown version mapping`() {
        diagnosticsTest("compose-material3-unknown-version")
    }

    @Test
    fun `compose material3 with known version mapping`() {
        diagnosticsTest("compose-material3-known-version")
    }
}
