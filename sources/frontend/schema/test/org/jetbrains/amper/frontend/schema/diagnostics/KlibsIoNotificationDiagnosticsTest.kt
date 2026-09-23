/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("gold-file")
class KlibsIoNotificationDiagnosticsTest : FrontendTestCaseBase(Path("testResources/diagnostics/klibs-io")) {

    @Test
    fun `automatic notification enabled on a non-multiplatform module`() {
        diagnosticsTest(caseName = "automatic-on-non-multiplatform")
    }

    @Test
    fun `automatic notification enabled on a multiplatform module`() {
        diagnosticsTest(caseName = "automatic-on-multiplatform")
    }

    @Test
    fun `automatic notification enabled in a template applied to a non-multiplatform module`() {
        diagnosticsTest(caseName = "automatic-in-template-on-non-multiplatform") { problems ->
            assertEquals(
                expected = [],
                actual = problems.map { it.diagnosticId },
                message = "Unexpected reported problems: ${problems.joinToString { "${it.diagnosticId}: ${it.message}" }}",
            )
        }
    }
}
