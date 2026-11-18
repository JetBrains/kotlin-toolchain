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
class ApplePlatformsKotlinSupportDiagnosticsTest : FrontendTestCaseBase(Path("testResources/diagnostics")) {

    @Test
    fun `macosX64 is not reported before Kotlin 2_3_20`() {
        diagnosticsTest("macosX64-with-kotlin-before-deprecation")
    }

    @Test
    fun `macosX64 is deprecated since Kotlin 2_3_20-Beta1`() {
        diagnosticsTest("macosX64-with-kotlin-2.3.20-beta1")
    }

    @Test
    fun `macosX64 is deprecated in Kotlin 2_4_21`() {
        diagnosticsTest("macosX64-with-kotlin-2.4.21")
    }

    @Test
    fun `macosX64 is deprecated with the default Kotlin version`() {
        diagnosticsTest("macosX64-with-default-kotlin")
    }

    @Test
    fun `macosX64 is unsupported since Kotlin 2_5_0-RC`() {
        diagnosticsTest("macosX64-with-kotlin-2.5.0-RC")
    }

    @Test
    fun `macosX64 is unsupported in Kotlin 2_5_0`() {
        diagnosticsTest("macosX64-with-kotlin-2.5.0")
    }

    @Test
    fun `macosArm64 is not reported with Kotlin 2_5_0`() {
        diagnosticsTest("macosArm64-with-kotlin-2.5.0")
    }

    @Test
    fun `tvosX64 is deprecated in Kotlin 2_4_21`() {
        diagnosticsTest("tvosX64-with-kotlin-2.4.21")
    }

    @Test
    fun `tvosX64 is unsupported in Kotlin 2_5_0`() {
        diagnosticsTest("tvosX64-with-kotlin-2.5.0")
    }

    @Test
    fun `macosX64 and tvosX64 are both reported`() {
        diagnosticsTest("macosX64-and-tvosX64-with-kotlin-2.5.0")
    }

    @Test
    fun `watchosArm32 is not reported before Kotlin 2_4_20`() {
        diagnosticsTest("watchosArm32-with-kotlin-before-deprecation")
    }

    @Test
    fun `watchosArm32 is deprecated since Kotlin 2_4_20-Beta2`() {
        diagnosticsTest("watchosArm32-with-kotlin-2.4.20-beta2")
    }

    @Test
    fun `watchosArm32 is still deprecated when macosX64 is already unsupported in Kotlin 2_5_0-Beta1`() {
        diagnosticsTest("macosX64-and-watchosArm32-with-kotlin-2.5.0-beta1")
    }

    @Test
    fun `watchosArm32 is unsupported since Kotlin 2_5_0-RC`() {
        diagnosticsTest("watchosArm32-with-kotlin-2.5.0-RC")
    }
}
