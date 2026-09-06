/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test

@Tag("gold-file")
class PathsDiagnosticsTest : FrontendTestCaseBase(Path("testResources") / "diagnostics" / "paths") {
    @Test
    fun `invalid path values`() {
        diagnosticsTest("invalid-path-values")
    }
}
