/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.agentdetection

import kotlin.io.path.Path

private val PiAgentDirectoryInPath = Regex("""\.pi[\\/]agent""")
private val DevinMarkerFile = Path("/opt/.devin")

/**
 * An AI agent that can run command-line tools.
 *
 * @property aiAgentName the canonical value of the `AI_AGENT` variable that identifies this agent.
 * See the [AI_AGENT standard](https://github.com/vercel/detect-agent#the-ai_agent-standard).
 */
enum class Agent(val aiAgentName: String) {
    Cursor("cursor"),
    CursorCli("cursor-cli"),
    Kimi("kimi"),
    Grok("grok"),
    GeminiCli("gemini_cli"),
    Cline("cline"),
    Codex("codex_cli"),
    Antigravity("antigravity"),
    AugmentCli("augment-cli"),
    OpenCode("open_code"),
    Goose("goose"),
    Junie("junie"),
    Pi("pi"),
    ClaudeCode("claude_code"),
    Replit("replit"),
    GitHubCopilot("github-copilot"),
    Kiro("kiro"),
    OpenClaw("openclaw"),
    Devin("devin"),

    /** An agent that we don't know specifically, but that identified itself via the `AI_AGENT` variable. */
    Other("other"),
}

/** Other accepted (lowercase) `AI_AGENT` values, in addition to the canonical [Agent.aiAgentName]s. */
private val AgentNameAliases: Map<String, Agent> = mapOf(
    "gemini-cli" to Agent.GeminiCli,
    "codex" to Agent.Codex,
    "codex-cli" to Agent.Codex,
    "openai/codex" to Agent.Codex,
    "opencode" to Agent.OpenCode,
    "claude-code" to Agent.ClaudeCode,
    "claude" to Agent.ClaudeCode,
)

private val KnownAgentNames: Map<String, Agent> =
    Agent.entries.filter { it != Agent.Other }.associateBy { it.aiAgentName } + AgentNameAliases

/** Returns the agent that declared itself with the given [AI_AGENT][name] value, or [Agent.Other] if unknown. */
internal fun agentByDeclaredName(name: String): Agent = KnownAgentNames[name.lowercase()] ?: Agent.Other

/**
 * Returns the known agent whose markers are present in the environment, or null if there are none.
 *
 * Keep in sync with [agents.json](https://github.com/vercel/detect-agent/blob/main/agents.json).
 */
internal fun AgentDetectionEnvironment.detectAgentByMarkers(): Agent? = when {
    isAnySet("CURSOR_TRACE_ID") -> Agent.Cursor
    isAnySet("CURSOR_AGENT") || getEnv("CURSOR_EXTENSION_HOST_ROLE") == "agent-exec" -> Agent.CursorCli
    // KIMI_CODE_HOME may be configured outside an active session, so we rely on the plugin-scoped marker
    isAnySet("KIMI_PLUGIN_ROOT") -> Agent.Kimi
    isAnySet("GROK_PLUGIN_ROOT", "GROK_PLUGIN_DATA") -> Agent.Grok
    isAnySet("GEMINI_CLI") -> Agent.GeminiCli
    isAnySet("CLINE_ACTIVE") -> Agent.Cline
    isAnySet("CODEX_SANDBOX", "CODEX_CI", "CODEX_THREAD_ID", "CODEX_SANDBOX_NETWORK_DISABLED") -> Agent.Codex
    isAnySet("ANTIGRAVITY_AGENT", "ANTIGRAVITY_CLI_ALIAS") -> Agent.Antigravity
    isAnySet("AUGMENT_AGENT") -> Agent.AugmentCli
    isAnySet("OPENCODE_CLIENT", "OPENCODE") -> Agent.OpenCode
    isAnySet("GOOSE_PROVIDER") -> Agent.Goose
    isAnySet("JUNIE_DATA", "JUNIE_SHIM_PATH") -> Agent.Junie
    // Pi has no dedicated variable, but it prepends its '.pi/agent' directory to the PATH
    getEnv("PATH").orEmpty().contains(PiAgentDirectoryInPath) -> Agent.Pi
    // Claude Code (and Claude Cowork, which also sets these markers)
    isAnySet("CLAUDECODE", "CLAUDE_CODE") -> Agent.ClaudeCode
    isAnySet("REPL_ID") -> Agent.Replit
    isAnySet("COPILOT_MODEL", "COPILOT_ALLOW_ALL", "COPILOT_GITHUB_TOKEN") -> Agent.GitHubCopilot
    // TERM_PROGRAM=kiro is set by both the IDE terminal and the CLI agent, so we also require no TTY to avoid
    // detecting a human using the integrated terminal
    "kiro" in getEnv("TERM_PROGRAM").orEmpty() && !isStdoutTerminal() -> Agent.Kiro
    isAnySet("OPENCLAW_SHELL") -> Agent.OpenClaw
    fileExists(DevinMarkerFile) -> Agent.Devin
    else -> null
}

/** Returns true if any of the environment variables [names] is set and non-empty. */
private fun AgentDetectionEnvironment.isAnySet(vararg names: String): Boolean =
    names.any { !getEnv(it).isNullOrEmpty() }
