/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.schema.processing

import kotlinx.serialization.json.Json
import org.intellij.lang.annotations.Language
import org.jetbrains.amper.plugins.schema.model.PluginDeclarationsRequest
import org.jetbrains.amper.plugins.schema.model.withoutOrigin
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.test.TempDirExtension
import org.jetbrains.amper.test.assertEqualsWithDiff
import org.jetbrains.amper.test.golden.SystemDependentGoldenFormatter
import org.jetbrains.amper.test.golden.SystemLineSeparatorGoldenFormatter
import org.jetbrains.amper.test.golden.assertContentEqualsGoldenFile
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.writeText

abstract class SchemaProcessorTestBase {
    @RegisterExtension
    private val tempDirExtension = TempDirExtension()

    interface TestBuilder {
        fun givenSourceFile(
            @Language("kotlin") contents: String,
            packageName: String = "com.example",
            name: String = "plugin.kt",
        )

        fun expectPluginData(expectedPath: Path)

        fun givenPluginSettingsClassName(name: String)
    }

    protected fun runSchemaTest(
        block: TestBuilder.() -> Unit,
    ) {
        class Source(
            contents: String,
            packageName: String,
            val name: String,
        ) {
            val contents = """
                package $packageName
                import org.jetbrains.amper.plugins.*
                import java.nio.file.Path
                                
            """.trimIndent() + contents
            val contentsWithoutComments = this.contents.replace(MultiLineCommentRegex, "")
            val path = tempDirExtension.path.resolve(name)
        }

        val sources = mutableListOf<Source>()
        var pluginSettingsClassName: String? = null
        var expectedJsonPluginDataPath: Path? = null
        val builder = object : TestBuilder {
            override fun givenSourceFile(contents: String, packageName: String, name: String) {
                sources += Source(contents, packageName, name)
            }

            override fun expectPluginData(expectedPath: Path) {
                expectedJsonPluginDataPath = expectedPath
            }

            override fun givenPluginSettingsClassName(name: String) {
                pluginSettingsClassName = name
            }
        }
        builder.block()

        for (source in sources) {
            check(source.name.endsWith(".kt")) { "Kotlin source expected" }
            source.path.writeText(source.contentsWithoutComments)
        }

        val request = PluginDeclarationsRequest.Request(
            moduleName = "test-plugin",
            pluginSettingsClassName = pluginSettingsClassName,
            sourceDir = tempDirExtension.path,
        )

        val result = runSchemaProcessor(PluginDeclarationsRequest(listOf(request))).single()

        for (source in sources) {
            data class Marker(val contents: String, val position: Int)

            val markers = mutableListOf<Marker>()
            for (error in result.diagnostics) {
                for (range in error.source.offsetRangesInFile(source.path)) {
                    markers += Marker(contents = "/*{{*/", position = range.first)
                    markers += Marker(contents = "/*}} ${error.message} */", position = range.last)
                }
            }
            // Sorting all the markers to insert them one-by-one from the end to avoid offsets recalculation
            markers.sortByDescending { it.position }

            val markedContents = StringBuilder(source.contentsWithoutComments).run {
                for ((contents, position) in markers) {
                    insert(position, contents)
                }
                toString()
            }
            assertEqualsWithDiff(
                expected = source.contents,
                actual = markedContents,
            )
        }
        val format = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        @OptIn(SystemDependentGoldenFormatter::class)
        assertContentEqualsGoldenFile(
            goldenFile = expectedJsonPluginDataPath ?: error("The test should call expectPluginData()"),
            actualContent = format.encodeToString(result.declarations.withoutOrigin()),
            // TODO use gitattributes to enforce LF instead and make an exact comparison
            goldenFormatter = SystemLineSeparatorGoldenFormatter,
        )
    }
}

@OptIn(NonIdealDiagnostic::class)
private fun BuildProblemSource.offsetRangesInFile(path: Path): List<IntRange> = when (this) {
    is FileWithRangesBuildProblemSource -> listOfNotNull(offsetRange.takeIf { file == path })
    is MultipleLocationsBuildProblemSource -> sources.filter { it.file == path }.flatMap { it.offsetRangesInFile(path) }
    is FileBuildProblemSource -> error("Don't use file-global errors in kotlin schema diagnostic")
    GlobalBuildProblemSource -> error("Don't use global errors in kotlin schema diagnostic")
}

private val MultiLineCommentRegex = """/\*.*?\*/""".toRegex(RegexOption.DOT_MATCHES_ALL)
