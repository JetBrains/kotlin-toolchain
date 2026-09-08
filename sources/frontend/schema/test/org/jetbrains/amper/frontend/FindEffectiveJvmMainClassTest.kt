/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend

import org.jetbrains.amper.frontend.aomBuilder.doReadProjectModel
import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.readProjectContextWithTestFrontendResolver
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindEffectiveJvmMainClassTest : FrontendTestCaseBase(Path("testResources") / "find-effective-jvm-main-class") {
    @Test
    fun `explicit main class takes precedence over conventional entry point`() {
        assertEquals("configured.Main", readFragments("explicit-main-class").findEffectiveJvmMainClass())
    }

    @Test
    fun `conventional entry point uses package and ignores filename case`() {
        assertEquals("com.example.app.MainKt", readFragments("conventional-main-class").findEffectiveJvmMainClass())
    }

    @Test
    fun `conventional entry point prefers files closer to the source root`() {
        assertEquals("root.MainKt", readFragments("breadth-first-main-class").findEffectiveJvmMainClass())
    }

    @Test
    fun `returns null when neither explicit nor conventional entry point exists`() {
        assertNull(readFragments("no-main-class").findEffectiveJvmMainClass())
    }

    private fun readFragments(projectName: String): List<Fragment> {
        val problemReporter = CollectingProblemReporter()
        return context(problemReporter) {
            readProjectContextWithTestFrontendResolver(base / projectName)
                .doReadProjectModel(pluginData = emptyList(), mavenPluginXmls = emptyList())
                .modules
                .single()
                .fragments
        }
    }
}