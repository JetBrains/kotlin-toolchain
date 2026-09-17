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
class LocalSwiftPackageOutsideProjectDiagnosticsTest :
    FrontendTestCaseBase(Path("testResources/diagnostics/swiftpm-local-packages")) {

    @Test
    fun `warning when the package is outside the project, even without publishing`() {
        diagnosticsTest(caseName = "outside-project")
    }

    @Test
    fun `no warning when the package is inside the project`() {
        diagnosticsTest(caseName = "inside-project")
    }

    /**
     * The two local Swift package diagnostics are independent: being unresolvable for other developers of this project
     * and being unpublishable to a shared repository are different problems with different fixes.
     */
    @Test
    fun `both warnings when an outside package is also published`() {
        diagnosticsTest(caseName = "outside-project-published")
    }
}
