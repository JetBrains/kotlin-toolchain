/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnosticsTest
import org.jetbrains.amper.frontend.tree.TreeDiagnosticId
import org.jetbrains.amper.problems.reporting.BuildProblem
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the diagnostic backing [org.jetbrains.amper.frontend.api.NotBlank].
 */
class NotBlankValuesDiagnosticsTest : FrontendTestCaseBase(Path("testResources") / "diagnostics" / "not-blank") {

    @Test
    fun `empty version values`() {
        diagnosticsTest("empty-versions") { it.assertAllBlankValueProblems(expectedCount = 6) }
    }

    @Test
    fun `blank version values`() {
        diagnosticsTest("blank-versions") { it.assertAllBlankValueProblems(expectedCount = 6) }
    }

    @Test
    fun `empty version value in template is reported once`() {
        // The problem is located in the template file, so it cannot be annotated in the module file itself.
        diagnosticsTest("empty-version-in-template") { it.assertAllBlankValueProblems(expectedCount = 1) }
    }

    @Test
    fun `blank repository properties`() {
        diagnosticsTest("blank-repository") { it.assertAllBlankValueProblems(expectedCount = 2) }
    }

    @Test
    fun `blank publishing settings`() {
        diagnosticsTest("blank-publishing") { it.assertAllBlankValueProblems(expectedCount = 9) }
    }

    private fun List<BuildProblem>.assertAllBlankValueProblems(expectedCount: Int) {
        assertEquals(
            expected = List(expectedCount) { TreeDiagnosticId.BlankValueNotAllowed },
            actual = map { it.diagnosticId },
            message = "Unexpected reported problems: ${joinToString { "${it.diagnosticId}: ${it.message}" }}",
        )
    }
}
