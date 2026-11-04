/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.aomBuilder.doReadProjectModel
import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.readProjectContextWithTestFrontendResolver
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PowerAssertRuntimeDependencyTest :
    FrontendTestCaseBase(Path("testResources") / "power-assert-runtime") {

    @Test
    fun `PowerAssert runtime is injected only starting with Kotlin 2_4`() {
        val model = context(CollectingProblemReporter()) {
            readProjectContextWithTestFrontendResolver(base)
                .doReadProjectModel(pluginData = [], mavenPluginXmls = [])
        }

        fun hasRuntimeDependency(moduleName: String): Boolean =
            model.modules.single { it.userReadableName == moduleName }.fragments
                .flatMap { it.externalDependencies }
                .filterIsInstance<MavenDependencyBase>()
                .any { it.coordinates.artifactId == "kotlin-power-assert-runtime" }

        assertFalse(hasRuntimeDependency("kotlin-2-3-enabled"))
        assertTrue(hasRuntimeDependency("kotlin-2-4-enabled"))
    }
}
