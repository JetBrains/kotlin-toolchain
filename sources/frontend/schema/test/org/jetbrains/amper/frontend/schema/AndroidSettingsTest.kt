/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.frontend.aomBuilder.doReadProjectModel
import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.readProjectContextWithTestFrontendResolver
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

internal class AndroidSettingsTest : FrontendTestCaseBase(Path("testResources") / "android") {
    @Test
    fun `effective namespace`() {
        val testProjectDir = base.resolve("effective-namespace")
        val problemReporter = CollectingProblemReporter()
        val model = context(problemReporter) {
            val context = readProjectContextWithTestFrontendResolver(testProjectDir)
            runBlocking { context.doReadProjectModel(pluginData = emptyList(), mavenPluginXmls = emptyList()) }
        }
        val actualNamespaces = model.modules.associate { module ->
            val androidSettings = module.leafFragments.single { !it.isTest }.settings.android
            module.userReadableName to androidSettings.effectiveNamespace(module)
        }
        assertEquals(
            mapOf(
                "artifact-id-requires-sanitize" to "org.group._1my_artifact",
                "not-published" to "org.jetbrains.ktc.mangled.p1859976332",
                "published-with-artifact-id" to "org.group.artifact",
                "published-without-artifact-id" to "org.group.published_without_artifact_id",
                "set-namespace" to "org.group.explicit.namespace",
            ),
            actualNamespaces
        )
    }
}