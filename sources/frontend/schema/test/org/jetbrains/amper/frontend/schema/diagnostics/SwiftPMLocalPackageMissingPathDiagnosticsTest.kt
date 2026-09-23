/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.test.Test

// KTC-5938
@Tag("gold-file")
class SwiftPMLocalPackageMissingPathDiagnosticsTest :
    FrontendTestCaseBase(Path("testResources/diagnostics/swiftpm-local-packages")) {

    @Test
    fun `error and no exception when the local package path has no value`() {
        diagnosticsTest(caseName = "missing-path")
    }
}
