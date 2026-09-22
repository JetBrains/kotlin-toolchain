/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import com.github.ajalt.mordant.terminal.prompt
import org.jetbrains.amper.projectwizard.ComposeMultiplatformTargetPlatform
import org.jetbrains.amper.projectwizard.defaultProjectId
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewProjectPromptsTest {

    @Test
    fun `enter accepts the common interactive targets`() {
        val recorder = TerminalRecorder(
            ansiLevel = AnsiLevel.NONE,
            outputInteractive = true,
            inputInteractive = true,
        ).apply {
            // The fallback cancellation makes regressions fail instead of waiting forever for another selection.
            inputEvents.addAll([KeyboardEvent("Enter"), KeyboardEvent("c", ctrl = true)])
        }
        val terminal = Terminal(terminalInterface = recorder)

        val selected = terminal.promptForComposeMultiplatformTargets()

        val expected: Set<ComposeMultiplatformTargetPlatform> = [
            ComposeMultiplatformTargetPlatform.ANDROID,
            ComposeMultiplatformTargetPlatform.IOS,
            ComposeMultiplatformTargetPlatform.DESKTOP,
            ComposeMultiplatformTargetPlatform.WEB,
        ]
        assertEquals(expected, selected)
        assertEquals(KeyboardEvent("c", ctrl = true), recorder.inputEvents.single(), "Enter alone should suffice")
        assertFalse(ComposeMultiplatformTargetPlatform.SERVER in selected)
    }

    @Test
    fun `space toggles the target under the cursor`() {
        val recorder = interactiveRecorder().apply {
            // Android is selected by default, so a single Space deselects it.
            inputEvents.addAll([Space, KeyboardEvent("Enter"), KeyboardEvent("c", ctrl = true)])
        }

        val selected = Terminal(terminalInterface = recorder).promptForComposeMultiplatformTargets()

        assertFalse(ComposeMultiplatformTargetPlatform.ANDROID in selected)
        assertTrue(ComposeMultiplatformTargetPlatform.IOS in selected)
        assertEquals(KeyboardEvent("c", ctrl = true), recorder.inputEvents.single())
    }

    @Test
    fun `x no longer toggles targets now that space is the toggle key`() {
        val recorder = interactiveRecorder().apply {
            inputEvents.addAll([KeyboardEvent("x"), KeyboardEvent("Enter"), KeyboardEvent("c", ctrl = true)])
        }

        val selected = Terminal(terminalInterface = recorder).promptForComposeMultiplatformTargets()

        assertTrue(ComposeMultiplatformTargetPlatform.ANDROID in selected)
    }

    @Test
    fun `target prompt tells the user to toggle with space`() {
        val recorder = interactiveRecorder().apply {
            inputEvents.addAll([KeyboardEvent("Enter"), KeyboardEvent("c", ctrl = true)])
        }

        Terminal(terminalInterface = recorder).promptForComposeMultiplatformTargets()

        assertContains(recorder.output(), "[Space]")
        assertContains(recorder.output(), "\nspace toggle •")
    }

    @Test
    fun `project id prompt explains its impact and accepts the derived default`() {
        val recorder = interactiveRecorder(inputLines = [""])
        val terminal = Terminal(terminalInterface = recorder)
        val defaultProjectId = defaultProjectId("My App")

        val actualProjectId = terminal.promptForProjectId(defaultProjectId)

        assertEquals("org.example.myapp", actualProjectId)
        assertContains(recorder.output(), "Project id ($defaultProjectId): ")
        assertContains(recorder.output(), "Kotlin package")
        assertContains(recorder.output(), "Android namespace and application id")
        assertContains(recorder.output(), "iOS bundle id")
        assertContains(recorder.output(), "expensive to change later")
        assertEquals(1, recorder.output().occurrencesOf("The project id is used as"))
        assertTrue(recorder.output().indexOf("The project id is used as") < recorder.output().indexOf("Project id ("))
        assertTrue(recorder.output().endsWith("Project id ($defaultProjectId): "))
    }

    @Test
    fun `project id prompt retries with specific errors without repeating general guidance`() {
        val recorder = interactiveRecorder(
            inputLines = ["com.my_app", "myapp", "com.example.when", "org.example.valid"],
        )
        val terminal = Terminal(terminalInterface = recorder)

        val actualProjectId = terminal.promptForProjectId(defaultProjectId("Retry Demo"))

        assertEquals("org.example.valid", actualProjectId)
        assertEquals(1, recorder.output().occurrencesOf("The project id is used as"))
        assertContains(
            recorder.output(),
            "package name segments must contain only lowercase ASCII letters and digits",
        )
        assertContains(recorder.output(), "the package name must have at least 2 segments separated with a dot")
        assertContains(recorder.output(), "'when' is a reserved keyword")
        assertEquals(3, recorder.output().occurrencesOf("Invalid project id"))
        assertFalse("Use at least two non-empty dot-separated segments" in recorder.output())
        assertContains(
            recorder.output(),
            "lowercase ASCII letters and digits. See --help for --project-id requirements. Please try again.",
        )
        assertEquals(3, recorder.output().occurrencesOf("See --help for --project-id requirements."))
        assertFalse(".. Please try again." in recorder.output())
    }

    @Test
    fun `project path prompt retries and returns the resolved path`() {
        val recorder = interactiveRecorder(inputLines = ["\u0000", "foo/bar"])
        val workingDir = Path("/work")

        val projectPath = Terminal(terminalInterface = recorder).promptForProjectPath(workingDir)

        assertEquals(workingDir.resolve("foo/bar"), projectPath)
        assertContains(recorder.output(), "Project path is not valid on this system. Please try again.")
        assertEquals(2, recorder.output().occurrencesOf("Project path: "))
    }

    @Test
    fun `project prompts have a single trailing colon`() {
        val recorder = TerminalRecorder(
            ansiLevel = AnsiLevel.NONE,
            outputInteractive = true,
            inputInteractive = true,
        ).apply { inputLines.addAll(["hello", ""]) }
        val terminal = Terminal(terminalInterface = recorder)

        terminal.prompt(prompt = ProjectPathPrompt)
        assertEquals("Project path: ", recorder.output())

        recorder.clearOutput()
        val projectId = defaultProjectId("project")
        terminal.prompt(prompt = ProjectIdPrompt, default = projectId)
        assertEquals("Project id ($projectId): ", recorder.output())
    }

    private val Space = KeyboardEvent(" ")

    private fun interactiveRecorder(inputLines: List<String> = []): TerminalRecorder = TerminalRecorder(
        ansiLevel = AnsiLevel.NONE,
        outputInteractive = true,
        inputInteractive = true,
    ).apply { this.inputLines.addAll(inputLines) }

    private fun String.occurrencesOf(text: String): Int = Regex.fromLiteral(text).findAll(this).count()
}
