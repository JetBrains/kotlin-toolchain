/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.agentdetection

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDetectionTest {

    private fun detect(
        vararg env: Pair<String, String>,
        existingFiles: Set<Path> = [],
        stdoutIsTerminal: Boolean = true,
    ): Boolean = detectAgent(FakeEnvironment(mapOf(*env), existingFiles, stdoutIsTerminal)) != null

    private fun detectAgent(
        vararg env: Pair<String, String>,
        existingFiles: Set<Path> = [],
        stdoutIsTerminal: Boolean = true,
    ): Agent? = detectAgent(FakeEnvironment(mapOf(*env), existingFiles, stdoutIsTerminal))

    @Test
    fun `detection can be disabled explicitly`() {
        assertFalse(detect("KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED" to "false", "AI_AGENT" to "claude_code"))
        assertFalse(detect("KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED" to "FALSE", "CURSOR_AGENT" to "1"))
        assertTrue(detect("KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED" to "true", "AI_AGENT" to "claude_code"))
    }

    @Test
    fun `no agent when nothing is set`() {
        assertFalse(detect())
    }

    @Test
    fun `AI_AGENT with any non-blank value is detected`() {
        assertTrue(detect("AI_AGENT" to "custom-agent"))
        assertTrue(detect("AI_AGENT" to "my-custom-agent@v1.0"))
        assertTrue(detect("AI_AGENT" to "  custom-agent  "))
    }

    @Test
    fun `blank values are ignored`() {
        assertFalse(detect("AI_AGENT" to "   "))
        assertFalse(detect("AI_AGENT" to "", "CURSOR_TRACE_ID" to ""))
    }

    @Test
    fun `known env markers`() {
        val markers = [
            "CURSOR_TRACE_ID",
            "CURSOR_AGENT",
            "KIMI_PLUGIN_ROOT",
            "GROK_PLUGIN_ROOT",
            "GROK_PLUGIN_DATA",
            "GEMINI_CLI",
            "CLINE_ACTIVE",
            "CODEX_SANDBOX",
            "CODEX_CI",
            "CODEX_THREAD_ID",
            "CODEX_SANDBOX_NETWORK_DISABLED",
            "ANTIGRAVITY_AGENT",
            "ANTIGRAVITY_CLI_ALIAS",
            "AUGMENT_AGENT",
            "OPENCODE_CLIENT",
            "OPENCODE",
            "GOOSE_PROVIDER",
            "JUNIE_DATA",
            "JUNIE_SHIM_PATH",
            "CLAUDECODE",
            "CLAUDE_CODE",
            "REPL_ID",
            "COPILOT_MODEL",
            "COPILOT_ALLOW_ALL",
            "COPILOT_GITHUB_TOKEN",
            "OPENCLAW_SHELL",
        ]
        for (marker in markers) {
            assertTrue(detect(marker to "1"), "Agent not detected for $marker")
        }
    }

    @Test
    fun `cursor cli is detected from the extension host role`() {
        assertTrue(detect("CURSOR_EXTENSION_HOST_ROLE" to "agent-exec"))
        assertFalse(detect("CURSOR_EXTENSION_HOST_ROLE" to "something-else"))
    }

    @Test
    fun `KIMI_CODE_HOME alone is not enough`() {
        assertFalse(detect("KIMI_CODE_HOME" to "/tmp/kimi"))
    }

    @Test
    fun `pi is detected from the PATH`() {
        assertTrue(detect("PATH" to "/usr/bin:/home/me/.pi/agent/bin"))
        assertTrue(detect("PATH" to "C:\\Users\\me\\.pi\\agent\\bin;C:\\Windows"))
        assertFalse(detect("PATH" to "/usr/bin:/home/me/.pip/agent"))
    }

    @Test
    fun `cowork alone is not enough`() {
        assertFalse(detect("CLAUDE_CODE_IS_COWORK" to "1"))
    }

    @Test
    fun `kiro is only detected without a terminal`() {
        assertTrue(detect("TERM_PROGRAM" to "kiro", stdoutIsTerminal = false))
        assertFalse(detect("TERM_PROGRAM" to "kiro", stdoutIsTerminal = true))
    }

    @Test
    fun `devin is detected from its marker file`() {
        assertTrue(detect(existingFiles = [Path.of("/opt/.devin")]))
    }

    @Test
    fun `known markers are ignored on CI`() {
        val ciMarkers = [
            "CI" to "true",
            "CI" to "1",
            "TEAMCITY_VERSION" to "2025.11",
            "JENKINS_URL" to "https://jenkins.example.com",
            "TF_BUILD" to "True",
        ]
        for (ciMarker in ciMarkers) {
            assertFalse(detect(ciMarker, "COPILOT_GITHUB_TOKEN" to "secret"), "$ciMarker")
            assertFalse(detect(ciMarker, "CLAUDECODE" to "1"), "$ciMarker")
        }
    }

    @Test
    fun `falsy CI values do not count as CI`() {
        assertTrue(detect("CI" to "false", "CLAUDECODE" to "1"))
        assertTrue(detect("CI" to "0", "CLAUDECODE" to "1"))
        assertTrue(detect("CI" to "", "CLAUDECODE" to "1"))
    }

    @Test
    fun `explicit AI_AGENT is honored even on CI`() {
        assertTrue(detect("CI" to "true", "AI_AGENT" to "claude-code-action"))
    }

    @Test
    fun `falsy AI_AGENT values are ignored`() {
        for (value in ["false", "0", "FALSE", "no", "off", "none"]) {
            assertFalse(detect("AI_AGENT" to value), value)
        }
        assertFalse(detect("AI_AGENT" to "false", "CI" to "true", "CLAUDECODE" to "1"))
    }

    // ---- detectAgent tests ----

    @Test
    fun `detectAgent - no agent when nothing is set or detection is disabled`() {
        assertNull(detectAgent())
        assertNull(detectAgent("KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED" to "false", "AI_AGENT" to "codex"))
        assertNull(detectAgent("KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED" to "false", "CODEX_SANDBOX" to "1"))
    }

    @Test
    fun `detectAgent - known AI_AGENT names map to their agent, case-insensitively`() {
        for (name in ["codex", "codex_cli", "codex-cli", "openai/codex", "Codex", "CODEX_CLI"]) {
            assertEquals(Agent.Codex, detectAgent("AI_AGENT" to name), name)
        }
        assertEquals(Agent.ClaudeCode, detectAgent("AI_AGENT" to "claude_code"))
        assertEquals(Agent.GeminiCli, detectAgent("AI_AGENT" to "gemini_cli"))
    }

    @Test
    fun `detectAgent - unknown AI_AGENT names are Other`() {
        assertEquals(Agent.Other, detectAgent("AI_AGENT" to "custom-agent"))
        assertEquals(Agent.Other, detectAgent("AI_AGENT" to "claude-code-action", "CI" to "true"))
    }

    @Test
    fun `detectAgent - explicit AI_AGENT takes precedence over markers of other agents`() {
        assertEquals(Agent.ClaudeCode, detectAgent("AI_AGENT" to "claude-code", "CODEX_SANDBOX" to "1"))
        assertEquals(Agent.Other, detectAgent("AI_AGENT" to "custom-agent", "CODEX_THREAD_ID" to "abc"))
    }

    @Test
    fun `detectAgent - falsy AI_AGENT falls through to marker detection`() {
        assertEquals(Agent.Codex, detectAgent("AI_AGENT" to "false", "CODEX_SANDBOX" to "1"))
        assertEquals(Agent.Codex, detectAgent("AI_AGENT" to "none", "CODEX_THREAD_ID" to "abc"))
        assertEquals(Agent.Codex, detectAgent("AI_AGENT" to "", "CODEX_CI" to "1"))
        assertNull(detectAgent("AI_AGENT" to "false"))
    }

    @Test
    fun `detectAgent - markers identify the exact agent`() {
        assertEquals(Agent.Codex, detectAgent("CODEX_SANDBOX_NETWORK_DISABLED" to "1"))
        assertEquals(Agent.ClaudeCode, detectAgent("CLAUDECODE" to "1"))
        assertEquals(Agent.GeminiCli, detectAgent("GEMINI_CLI" to "1"))
        assertEquals(Agent.Cursor, detectAgent("CURSOR_TRACE_ID" to "1"))
        assertEquals(Agent.Junie, detectAgent("JUNIE_DATA" to "1"))
    }

    @Test
    fun `detectAgent - markers are ignored on CI but explicit AI_AGENT is honored`() {
        assertNull(detectAgent("CI" to "true", "CODEX_SANDBOX" to "1"))
        assertEquals(Agent.Codex, detectAgent("CI" to "true", "AI_AGENT" to "codex-cli"))
    }
}
