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
class LocalSwiftPackageInRemotePublicationDiagnosticsTest :
    FrontendTestCaseBase(Path("testResources/diagnostics/swiftpm-publication")) {

    @Test
    fun `warning when a remote repository is publishable`() {
        diagnosticsTest(caseName = "remote-repository")
    }

    @Test
    fun `warning when publishing to maven central`() {
        diagnosticsTest(caseName = "maven-central")
    }

    @Test
    fun `no warning when only maven local is publishable`() {
        diagnosticsTest(caseName = "maven-local-only")
    }

    @Test
    fun `no warning when publishing is disabled`() {
        diagnosticsTest(caseName = "publishing-disabled")
    }

    @Test
    fun `no warning for remote swift packages`() {
        diagnosticsTest(caseName = "remote-package")
    }
}
