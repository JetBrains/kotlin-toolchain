/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import kotlin.io.path.Path
import kotlin.test.Test

class MavenCentralRequirementsDiagnosticsTest : FrontendTestCaseBase(Path("testResources/diagnostics/maven-central")) {

    @Test
    fun `test everything missing`() {
        diagnosticsTest(caseName = "everything-missing")
    }

    @Test
    fun `publishing disabled explicitly`() {
        diagnosticsTest(caseName = "publishing-false-explicit")
    }

    @Test
    fun `explicit checksums list missing required`() {
        diagnosticsTest(caseName = "overridden-checksums-missing-required")
    }

    @Test
    fun `module description not required if pom description is set`() {
        diagnosticsTest(caseName = "pom-description-set")
    }
}
