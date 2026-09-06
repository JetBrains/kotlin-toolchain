/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.contexts.PathCtx
import org.jetbrains.amper.frontend.contexts.PlatformCtx
import org.jetbrains.amper.frontend.diagnostics.TemplateApplicationLoop
import org.jetbrains.amper.frontend.helpers.DiagnosticsTreeTestRun
import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.diagnoseModuleRead
import org.jetbrains.amper.frontend.helpers.readAndRefineModule
import org.jetbrains.amper.frontend.helpers.readModuleWithTemplatesAndGetProblems
import org.jetbrains.amper.frontend.helpers.testModuleRead
import org.jetbrains.amper.frontend.helpers.testRefineModule
import org.jetbrains.amper.frontend.helpers.testRefineModuleWithTemplates
import org.jetbrains.amper.frontend.types.SchemaTypingContext
import org.jetbrains.amper.plugins.schema.model.PluginData
import org.jetbrains.amper.plugins.schema.model.PluginSettingsSearchResult
import org.jetbrains.amper.plugins.schema.model.SourceLocation
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("gold-file")
class TreeTests : FrontendTestCaseBase(Path(".") / "testResources" / "valueTree") {

    @Test
    fun `all settings read`() =
        testModuleRead("all-module-settings")

    @Test
    fun `all settings merge for jvm`() = testRefineModule(
        "all-module-settings",
        selectedContexts = platformCtxs("jvm"),
        expectPostfix = "-merge-jvm-result.json",
    )

    @Test
    fun `all settings merge for android`() = testRefineModule(
        "all-module-settings",
        selectedContexts = platformCtxs("android"),
        expectPostfix = "-merge-android-result.json",
    )

    @Test
    fun `unknown properties with misnomer`() {
        diagnoseModuleRead("misnomer-did-you-mean")
    }

    @Test
    fun `merge with templates`() = testRefineModuleWithTemplates(
        "with-templates",
        selectedContexts = { platformCtxs("jvm") + PathCtx(it.toNioPath(), null) },
    )

    @Test
    fun `merge with nested templates`() = testRefineModuleWithTemplates(
        "with-nested-templates",
        selectedContexts = { platformCtxs("jvm") + PathCtx(it.toNioPath(), null) },
    )

    @Test
    fun `template loop`() {
        // TODO: We do not report template issues in the module that applies them so we have to manually check
        //  problems here.
        val problems = readModuleWithTemplatesAndGetProblems(
            caseName = "template-loop",
            selectedContexts = { listOf(PathCtx(it.toNioPath(), null)) },
        )
        val conflicts = problems.filterIsInstance<TemplateApplicationLoop>()
        assertEquals(1, conflicts.size, "Expected exactly one conflict, got: $problems")
        val conflict = conflicts.single()
        assertEquals("Template application loop detected: t1 -> t2 -> t3 -> t1", conflict.message)
    }

    @Test
    fun `template diamond with resolved conflict`() = testRefineModuleWithTemplates(
        "template-diamond",
        selectedContexts = { listOf(PathCtx(it.toNioPath(), null)) }
    )

    @Test
    fun `template diamond with unresolved conflict`() {
        // TODO: We do not report template issues in the module that applies them so we have to manually check
        //  problems here.
        val problems = readModuleWithTemplatesAndGetProblems(
            caseName = "template-diamond-with-conflict",
            selectedContexts = { listOf(PathCtx(it.toNioPath(), null)) },
        )
        val conflicts = problems.filterIsInstance<ConflictingProperties>()
        assertEquals(1, conflicts.size, "Expected exactly one conflict, got: $problems")
        val conflict = conflicts.single()
        assertEquals(2, conflict.keyValues.size, "Expected exactly two conflicting properties, got: $conflict")
        assertTrue(conflict.keyValues.all { it.key == "release" })
    }

    @Suppress("unused")
    class CustomPluginSchema : SchemaNode() {
        val foo: Int by value()
        val bar: String by value()
    }

    @Test
    fun `read module file with custom properties`() = testModuleRead(
        "with-custom-properties",
        types = SchemaTypingContext(
            pluginData = testPluginData(),
        )
    )

    @Test
    fun `read module file with custom properties diagnostics`() = diagnoseModuleRead(
        "with-custom-properties-diagnostics",
        types = SchemaTypingContext(
            pluginData = testPluginData(),
        )
    )

    @Test
    fun `context conflicts on a scalar`() = DiagnosticsTreeTestRun(
        caseName = "context-conflicts-scalar",
        testCase = this,
        types = SchemaTypingContext(),
        treeBuilder = readAndRefineModule(platformCtxs("jvm")),
    ).doTest()

    @Test
    fun `no context conflicts if value is the same`() = DiagnosticsTreeTestRun(
        caseName = "context-no-conflicts",
        testCase = this,
        types = SchemaTypingContext(),
        treeBuilder = readAndRefineModule(platformCtxs("jvm")),
    ).doTest()

    @Test
    fun `conflicting template values are reported`() {
        // TODO: We do not report template issues in the module that applies them so we have to manually check
        //  problems here.
        val problems = readModuleWithTemplatesAndGetProblems(
            caseName = "template-conflicts",
            selectedContexts = { platformCtxs("jvm") + PathCtx(it.toNioPath(), null) },
        )
        val conflicts = problems.filterIsInstance<ConflictingProperties>()
        assertEquals(1, conflicts.size, "Expected exactly one conflict, got: $problems")
        val conflict = conflicts.single()
        assertEquals(2, conflict.keyValues.size, "Expected exactly two conflicting properties, got: $conflict")
        assertTrue(conflict.keyValues.all { it.key == "languageVersion" })
    }

    @Test
    fun `context conflicts are not reported if resolved in a more specific context`() = DiagnosticsTreeTestRun(
        caseName = "context-conflicts-resolved",
        testCase = this,
        types = SchemaTypingContext(),
        treeBuilder = readAndRefineModule(platformCtxs("jvm")),
    ).doTest()

    private fun platformCtxs(vararg values: String) =
        values.map { PlatformCtx(it, null) }.toSet()

    private fun testPluginData(): List<PluginData> {
        val stubLocation = SourceLocation(Path("/"), -1..-1)
        return listOf(
            PluginData(
                id = PluginData.Id("myPlugin"),
                pluginSettingsSearchResult = PluginSettingsSearchResult.Success(
                    name = PluginData.SchemaName("com.example", listOf("CustomPluginSchema"))
                ),
                source = PluginData.Source.Local(Path("/")),
                declarations = PluginData.Declarations(
                    classes = listOf(
                        PluginData.ClassData(
                            name = PluginData.SchemaName("com.example", listOf("CustomPluginSchema")),
                            origin = stubLocation,
                            properties = listOf(
                                PluginData.ClassData.Property(
                                    name = "foo",
                                    type = PluginData.Type.IntType(),
                                    origin = stubLocation,
                                ),
                                PluginData.ClassData.Property(
                                    name = "bar",
                                    type = PluginData.Type.StringType(),
                                    origin = stubLocation,
                                ),
                            )
                        )
                    ),
                ),
            )
        )
    }
}