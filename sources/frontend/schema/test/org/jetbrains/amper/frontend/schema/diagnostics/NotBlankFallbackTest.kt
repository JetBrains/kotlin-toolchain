/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.aomBuilder.doReadProjectModel
import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.TestFrontendPathResolver
import org.jetbrains.amper.frontend.helpers.TestProjectContext
import org.jetbrains.amper.frontend.project.AmperFrontendProjectRoot
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.DiscouragedDirectDefaultVersionAccess
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Blank values on [org.jetbrains.amper.frontend.api.NotBlank] properties must be treated like any other invalid
 * value by the parser: replaced by an error node, so the property falls back to its default (if any).
 */
internal class NotBlankFallbackTest : FrontendTestCaseBase(Path("testResources") / "diagnostics" / "not-blank") {

    // Blank values for @NotBlank properties are reported as errors, but we still build
    // a model sometimes, and in that case the model must not contain blanks.
    // Error nodes in the tree should be replaced with default values when there is one.
    @OptIn(DiscouragedDirectDefaultVersionAccess::class)
    @Test
    fun `blank versions fall back to the default versions`() {
        val settings = readSingleModule("empty-versions").fragments.first().settings

        assertEquals(DefaultVersions.compose, settings.compose.version)
        assertEquals(DefaultVersions.composeHotReload, settings.compose.experimental.hotReload.version)
        assertEquals(DefaultVersions.ktor, settings.ktor.version)
        assertEquals(DefaultVersions.kotlinxSerialization, settings.kotlin.serialization.version)
    }

    @Test
    fun `objects with a blank required property are dropped from lists`() {
        // The first repository has a blank url, which is required and has no default, so the whole repository is
        // dropped, exactly like it would be for any other invalid value. The second one only has a blank `id`,
        // which falls back to its default (the url).
        val repositories = readSingleModule("blank-repository").mavenResolveRepositories

        assertTrue(repositories.none { it.url.isBlank() }, "Blank-url repository should be dropped: $repositories")
        val declaredRepo = repositories.single { it.url == "https://example.com/repo" }
        assertEquals("https://example.com/repo", declaredRepo.id, "Blank id should fall back to the url default")
    }

    private fun readSingleModule(caseName: String): AmperModule {
        val problemReporter = CollectingProblemReporter()
        val pathResolver = TestFrontendPathResolver()
        val moduleFile = pathResolver.loadVirtualFile((base / "$caseName.yaml").absolute())
        val projectContext = TestProjectContext(
            projectRoot = AmperFrontendProjectRoot(pathResolver.loadVirtualFile(base.absolute())),
            amperModuleFiles = [moduleFile],
            frontendPathResolver = pathResolver,
        )
        val model = context(problemReporter) {
            projectContext.doReadProjectModel(pluginData = emptyList(), mavenPluginXmls = emptyList())
        }
        assertTrue(
            problemReporter.problems.isNotEmpty(),
            "Expected the blank values to still be reported as problems",
        )
        return model.modules.single()
    }
}
