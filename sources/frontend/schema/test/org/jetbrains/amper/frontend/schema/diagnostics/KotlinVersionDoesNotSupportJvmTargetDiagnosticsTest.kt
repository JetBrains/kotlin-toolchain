/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.test.Test

@Tag("gold-file")
class KotlinVersionDoesNotSupportJvmTargetDiagnosticsTest : FrontendTestCaseBase(Path("testResources/diagnostics")) {

    @Test
    fun `kotlin stable version does not support jdk higher than its max`() {
        diagnosticsTest("kotlin-version-does-not-support-jdk")
    }

    @Test
    fun `kotlin beta version does not support jdk higher than its max`() {
        diagnosticsTest("kotlin-version-beta-fails-on-jdk-higher-than-max")
    }

    @Test
    fun `kotlin 2_3 supports jdk 25`() {
        diagnosticsTest("kotlin-version-supports-jdk")
    }

    @Test
    fun `kotlin 2_5 beta supports jdk 28`() {
        diagnosticsTest("kotlin-version-beta-supports-its-highest-jdk")
    }

    @Test
    fun `kotlin 2_4 does not support jdk 100`() {
        diagnosticsTest("jdk-version-too-high-with-no-known-matching-kotlin")
    }

    @Test
    fun `kotlin newer than known rules is not reported`() {
        diagnosticsTest("kotlin-version-newer-than-known-supports-any-jdk")
    }

    @Test
    fun `kotlin 2_2 does not support the default jdk`() {
        diagnosticsTest("kotlin-version-does-not-support-default-jdk")
    }

    @Test
    fun `kotlin 2_2 does not support jvm release 25`() {
        diagnosticsTest("kotlin-version-does-not-support-jvm-release")
    }

    @Test
    fun `kotlin 2_2 supports jvm release 24 despite jdk 25`() {
        diagnosticsTest("kotlin-version-supports-higher-jdk-if-release-low-enough")
    }

    @Test
    fun `kotlin 2_4 does not support jvm release 100`() {
        diagnosticsTest("jvm-release-newer-than-known-kotlin-version")
    }

    @Test
    fun `null jvm release is not reported`() {
        diagnosticsTest("null-jvm-release-is-not-reported")
    }
}