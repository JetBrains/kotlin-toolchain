/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import kotlin.io.path.Path
import kotlin.test.Test

class KotlinVersionDoesNotSupportJdkDiagnosticsTest : FrontendTestCaseBase(Path("testResources/diagnostics")) {

    @Test
    fun `kotlin 2_2 does not support jdk 25`() {
        diagnosticsTest("kotlin-version-does-not-support-jdk")
    }

    @Test
    fun `kotlin 2_3 supports jdk 25`() {
        diagnosticsTest("kotlin-version-supports-jdk")
    }
    
    @Test
    fun `kotlin 2_4 doest not support jdk 100`() {
        diagnosticsTest("jdk-version-newer-than-known-kotlin-version")
    }

    @Test
    fun `kotlin newer than known rules is not reported`() {
        diagnosticsTest("kotlin-version-newer-than-known-supports-jdk")
    }
}