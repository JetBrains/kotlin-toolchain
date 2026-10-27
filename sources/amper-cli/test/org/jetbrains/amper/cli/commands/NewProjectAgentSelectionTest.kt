/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parsers.CommandLineParser
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.jetbrains.amper.projectwizard.AgentsMdPath
import org.jetbrains.amper.projectwizard.AiAgent
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewProjectAgentSelectionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `enter accepts installation and all agents are preselected`() {
        val recorder = interactiveRecorder("").apply {
            inputEvents.addAll([KeyboardEvent("Enter"), Cancel])
        }

        val selected = Terminal(terminalInterface = recorder).promptForAiAgents()

        assertEquals(AiAgent.entries.toSet(), selected)
        assertEquals(Cancel, recorder.inputEvents.single())
        assertContains(recorder.output(), "Install AI agent configuration? (Y/n)")
    }

    @Test
    fun `space deselects an agent without changing the others`() {
        val recorder = interactiveRecorder("y").apply {
            inputEvents.addAll([KeyboardEvent(" "), KeyboardEvent("Enter"), Cancel])
        }

        val selected = Terminal(terminalInterface = recorder).promptForAiAgents()

        assertEquals(AiAgent.entries.toSet() - AiAgent.CLAUDE, selected)
        assertEquals(Cancel, recorder.inputEvents.single())
    }

    @Test
    fun `declining installation skips the agent picker`() {
        val recorder = interactiveRecorder("n").apply { inputEvents.add(Cancel) }

        val selected = Terminal(terminalInterface = recorder).promptForAiAgents()

        assertTrue(selected.isEmpty())
        assertFalse("Please select at least one AI agent" in recorder.output())
        assertEquals(Cancel, recorder.inputEvents.single())
    }

    @Test
    fun `EOF and cancellation abort instead of accepting defaults`() {
        assertFailsWith<PrintMessage> {
            Terminal(terminalInterface = interactiveRecorder()).promptForAiAgents()
        }
        val recorder = interactiveRecorder("y").apply { inputEvents.add(Cancel) }
        assertFailsWith<PrintMessage> {
            Terminal(terminalInterface = recorder).promptForAiAgents()
        }
    }

    @Test
    fun `empty selection cannot be confirmed and must be retried`() {
        val recorder = interactiveRecorder("y").apply {
            for (index in AiAgent.entries.indices) {
                inputEvents.add(KeyboardEvent(" "))
                if (index != AiAgent.entries.lastIndex) inputEvents.add(KeyboardEvent("ArrowDown"))
            }
            inputEvents.addAll([KeyboardEvent("Enter"), KeyboardEvent(" "), KeyboardEvent("Enter"), Cancel])
        }

        val selected = Terminal(terminalInterface = recorder).promptForAiAgents()

        assertEquals(setOf(AiAgent.CLAUDE), selected)
        assertEquals(Cancel, recorder.inputEvents.single())
    }

    @ParameterizedTest
    @ValueSource(strings = ["new", "init"])
    fun `declining MCP configuration still preserves shared instructions`(commandName: String) = runTestWithMdc {
        val recorder = interactiveRecorder("", "n")
        val outputDir = if (commandName == "new") tempDir / "myapp" else tempDir
        val command = if (commandName == "new") NewCommand() else InitCommand()
        val locationArgs = if (commandName == "new") [outputDir.toString()] else ["--target-dir=$outputDir"]

        val generationArgs: List<String> = ["--target-platform=desktop", "--no-git"]
        runCommand(command, recorder, locationArgs + generationArgs)

        val output = recorder.output()
        assertContains(output, "Project id")
        assertContains(output, "Install AI agent configuration?")
        assertTrue(output.indexOf("Project id") < output.indexOf("Install AI agent configuration?"))
        assertTrue(output.indexOf("Install AI agent configuration?") < output.indexOf("Project successfully generated"))
        assertFalse((outputDir / ".mcp.json").exists())
        assertFalse((outputDir / AgentsMdPath).exists())
    }

    @ParameterizedTest
    @ValueSource(strings = ["--no-ai", "--agent=antigravity", "--agent=other"])
    fun `explicit AI options skip both interactive AI questions`(option: String) = runTestWithMdc {
        val recorder = interactiveRecorder().apply { inputEvents.add(Cancel) }
        val outputDir = tempDir / "myapp"

        runCommand(
            NewCommand(), recorder,
            [outputDir.toString(), "--target-platform=desktop", "--project-id=com.example.myapp", "--no-git", option],
        )

        assertFalse("Install AI agent configuration?" in recorder.output())
        assertFalse("Please select at least one AI agent" in recorder.output())
        assertEquals(Cancel, recorder.inputEvents.single())
        assertEquals(option == "--agent=antigravity", (outputDir / ".agents/mcp_config.json").exists())
        assertFalse((outputDir / ".mcp.json").exists())
        assertEquals(option != "--no-ai", (outputDir / AgentsMdPath).exists())
    }

    private suspend fun runCommand(
        command: SuspendingCliktCommand,
        recorder: TerminalRecorder,
        args: List<String>,
    ) {
        command.context { terminal = Terminal(terminalInterface = recorder) }
        val result = CommandLineParser.parse(command, args)
        CommandLineParser.run(result.invocation) { it.run() }
    }

    private fun interactiveRecorder(vararg inputLines: String): TerminalRecorder = TerminalRecorder(
        ansiLevel = AnsiLevel.NONE,
        inputInteractive = true,
        outputInteractive = true,
    ).apply { this.inputLines.addAll(inputLines.toList()) }

    private val Cancel = KeyboardEvent("c", ctrl = true)
}
