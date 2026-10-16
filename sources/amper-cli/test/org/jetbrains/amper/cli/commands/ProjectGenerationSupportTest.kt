/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.jetbrains.amper.system.info.OsFamily
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectGenerationSupportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `cd prefix enters a child directory`() {
        val prefix = cdPrefix(Path("/work/myapp"), workingDir = Path("/work"), osFamily = OsFamily.Linux)

        assertEquals("cd myapp && ", prefix)
    }

    @Test
    fun `cd prefix is empty for the current directory`() {
        val prefix = cdPrefix(Path("/work"), workingDir = Path("/work"), osFamily = OsFamily.Linux)

        assertEquals("", prefix)
    }

    @Test
    fun `cd prefix quotes child directories containing spaces`() {
        val prefix = cdPrefix(Path("/work/My App"), workingDir = Path("/work"), osFamily = OsFamily.Linux)

        assertEquals("cd 'My App' && ", prefix)
    }

    @Test
    fun `cd prefix uses a Windows command separator`() {
        val prefix = cdPrefix(Path("/work/My App"), workingDir = Path("/work"), osFamily = OsFamily.Windows)

        assertEquals("cd \"My App\"; ", prefix)
    }

    @Test
    fun `interactive conflicts can be confirmed for overwriting`() {
        (tempDir / "project.yaml").writeText("existing project")
        val recorder = TerminalRecorder().apply { inputLines += "y" }
        val terminal = Terminal(terminalInterface = recorder)

        val shouldOverwrite = terminal.shouldOverwriteConflictingPaths(
            relativePaths = ["project.yaml"],
            outputDir = tempDir,
        )

        assertTrue(shouldOverwrite)
        assertContains(recorder.output(), "Files that would be overwritten by the template:")
        assertContains(recorder.output(), "  project.yaml")
        assertContains(recorder.output(), "Overwrite the conflicting files and directories?")
    }

    @Test
    fun `declining interactive overwrite aborts project generation`() {
        val projectFile = tempDir / "project.yaml"
        projectFile.writeText("existing project")
        val recorder = TerminalRecorder().apply { inputLines += "n" }
        val terminal = Terminal(terminalInterface = recorder)

        assertFailsWith<PrintMessage> {
            terminal.shouldOverwriteConflictingPaths(
                relativePaths = ["project.yaml"],
                outputDir = tempDir,
            )
        }
        assertTrue(projectFile.exists())
        assertContains(projectFile.readText(), "existing project")
    }
}
