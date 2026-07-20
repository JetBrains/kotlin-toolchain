/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.messages

import org.jetbrains.amper.frontend.helpers.ModifiablePsiIntelliJApplicationConfigurator
import org.jetbrains.amper.intellij.MockProjectInitializer
import org.jetbrains.amper.problems.reporting.BuildProblemImpl
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.test.TempDirExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test

@OptIn(NonIdealDiagnostic::class)
class ProblemReporterRenderingTest {
    @RegisterExtension
    private val tempDirExtension = TempDirExtension()

    @Test
    fun `reporting problem without file`() {
        val problem = BuildProblemImpl(
            diagnosticId = TestDiagnosticId,
            source = GlobalBuildProblemSource,
            message = "Test message",
            level = Level.Error,
            type = BuildProblemType.Generic,
        )
        assertEquals("Test message", renderMessage(problem))
    }

    @Test
    fun `reporting problem with file but no line`() {
        val problem = BuildProblemImpl(
            diagnosticId = TestDiagnosticId,
            source = TestFileProblemSource(Path("test.txt")),
            message = "Test message",
            level = Level.Error,
            type = BuildProblemType.Generic,
        )
        assertEquals("test.txt: Test message", renderMessage(problem))
    }

    @Test
    fun `reporting problem with file and line`() {
        MockProjectInitializer.initMockProject(ModifiablePsiIntelliJApplicationConfigurator)
        val testFile = tempDirExtension.path / "test.yaml"
        testFile.writeText("""
            Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.
            Excepteur sint occaecat cupidatat non proident,
            sunt in culpa qui officia deserunt mollit anim id est laborum.
        """.trimIndent())
        val problem = BuildProblemImpl(
            diagnosticId = TestDiagnosticId,
            source = TestFileWithRangesProblemSource(
                testFile,
                offsetRange = IntRange(150, 11),
            ),
            message = "Test message",
            level = Level.Error,
            type = BuildProblemType.Generic,
        )
        assertEquals("${testFile}:2:48: Test message", renderMessage(problem))
    }

    @Test
    fun `reporting problem with multiple locations`() {
        MockProjectInitializer.initMockProject(ModifiablePsiIntelliJApplicationConfigurator)
        val testFile1 = tempDirExtension.path / "test.yaml"
        val testFile2 = tempDirExtension.path / "test2.yaml"
        testFile1.writeText("""
            Lorem ipsum dolor sit amet, consectetur adipiscing elit,
            sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
            Ut enim ad minim veniam,
            quis nostrud exercitation ullamco laboris nisi ut 
            aliquip ex ea commodo consequat.
            Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.
            Excepteur sint occaecat cupidatat non proident,
            sunt in culpa qui officia deserunt mollit anim id est laborum.
        """.trimIndent())
        testFile2.writeText("""
            Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.
            Excepteur sint occaecat cupidatat non proident,
            sunt in culpa qui officia deserunt mollit anim id est laborum.
        """.trimIndent())

        val location1 = TestFileWithRangesProblemSource(
            testFile1,
            offsetRange = IntRange(10, 15),
        )
        val location2 = TestFileWithRangesProblemSource(
            testFile2,
            offsetRange = IntRange(150, 165),
        )
        val location3 = TestFileWithRangesProblemSource(
            testFile2,
            offsetRange = IntRange(200, 210),
        )
        val problem = BuildProblemImpl(
            diagnosticId = TestDiagnosticId,
            source = MultipleLocationsBuildProblemSource(
                location1, location2, location3,
                groupingMessage = "Encountered in:",
            ),
            message = "Test message",
            level = Level.Error,
            type = BuildProblemType.Generic,
        )
        assertEquals(
            """
            Test message
            ╰─ Encountered in:
               ├─ ${testFile1}:1:11
               ├─ ${testFile2}:2:48
               ╰─ ${testFile2}:3:50
            """.trimIndent(), renderMessage(problem)
        )
    }

    private data class TestFileProblemSource(override val file: Path) : FileBuildProblemSource

    private data class TestFileWithRangesProblemSource(
        override val file: Path,
        override val offsetRange: IntRange,
    ) : FileWithRangesBuildProblemSource

    private object TestDiagnosticId : DiagnosticId
}
