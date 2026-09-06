/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test

@Tag("gold-file")
class ComposeHotReloadVersionConflictDiagnosticsTest : FrontendTestCaseBase(
    Path("testResources") / "diagnostics" / "compose-hot-reload-version-conflict",
) {
    @Test
    fun `compose hot reload version conflict across jvm apps`() {
        diagnosticsTest(
            caseName = "app-a/module",
            additionalFiles = listOf(
                "app-b/module.yaml",
                "app-c/module.yaml",
            ),
        )
    }
}
