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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectGenerationSupportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `next steps combine the cd and build commands into one copyable line`() {
        val recorder = TerminalRecorder()
        val terminal = Terminal(terminalInterface = recorder)

        terminal.printProjectGeneratedNextSteps(
            outputDir = Path("/work/myapp"),
            workingDir = Path("/work"),
            osFamily = OsFamily.Linux,
        )

        val output = recorder.output()
        assertContains(output, "cd myapp && kotlin build")
        assertContains(output, "IDE with the Kotlin Toolchain plugin")
    }

    @Test
    fun `next steps omit cd for generation in the current directory`() {
        val recorder = TerminalRecorder()
        val terminal = Terminal(terminalInterface = recorder)

        terminal.printProjectGeneratedNextSteps(
            outputDir = Path("/work"),
            workingDir = Path("/work"),
            osFamily = OsFamily.Linux,
        )

        assertFalse("cd " in recorder.output())
        assertContains(recorder.output(), "kotlin build")
    }

    @Test
    fun `next steps quote child directories containing spaces`() {
        val recorder = TerminalRecorder()
        val terminal = Terminal(terminalInterface = recorder)

        terminal.printProjectGeneratedNextSteps(
            outputDir = Path("/work/My App"),
            workingDir = Path("/work"),
            osFamily = OsFamily.Linux,
        )

        assertContains(recorder.output(), "cd 'My App' && kotlin build")
    }

    @Test
    fun `next steps quote child directories the Windows way`() {
        val recorder = TerminalRecorder()
        val terminal = Terminal(terminalInterface = recorder)

        terminal.printProjectGeneratedNextSteps(
            outputDir = Path("/work/My App"),
            workingDir = Path("/work"),
            osFamily = OsFamily.Windows,
        )

        assertContains(recorder.output(), "cd \"My App\" && kotlin build")
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
