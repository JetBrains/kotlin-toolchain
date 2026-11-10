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
 * @property aiAgentEnvPrefixes the possible prefixes of the `AI_AGENT` variable values that identify this agent.
 * There are several competing standards, see [AI_AGENT standard](https://github.com/vercel/detect-agent#the-ai_agent-standard)
 * or [this proposal](https://github.com/agentsmd/agents.md/issues/136).
 * @property executable the name (without extension) of the executable of this agent, used to detect whether it is
 * installed (see [detectInstalledAgents]), or null if it's not a command-line tool we can look for.
 */
enum class Agent(
    val aiAgentEnvPrefixes: List<String>,
    val executable: String? = null,
) {
    Cursor(aiAgentEnvPrefixes = ["cursor"]),
    CursorCli(aiAgentEnvPrefixes = ["cursor-cli"], executable = "cursor-agent"),
    Kimi(aiAgentEnvPrefixes = ["kimi"], executable = "kimi"),
    Grok(aiAgentEnvPrefixes = ["grok"], executable = "grok"),
    GeminiCli(aiAgentEnvPrefixes = ["gemini_cli", "gemini-cli"], executable = "gemini"),
    Cline(aiAgentEnvPrefixes = ["cline"], executable = "cline"),
    Codex(aiAgentEnvPrefixes = ["codex", "codex_cli", "codex-cli", "openai/codex"], executable = "codex"),
    Antigravity(aiAgentEnvPrefixes = ["antigravity"], executable = "antigravity"),
    AugmentCli(aiAgentEnvPrefixes = ["augment-cli"], executable = "auggie"),
    OpenCode(aiAgentEnvPrefixes = ["open_code", "opencode"], executable = "opencode"),
    Goose(aiAgentEnvPrefixes = ["goose"], executable = "goose"),
    Junie(aiAgentEnvPrefixes = ["junie"], executable = "junie"),
    Pi(aiAgentEnvPrefixes = ["pi"], executable = "pi"),
    ClaudeCode(aiAgentEnvPrefixes = ["claude_code", "claude", "claude-code"], executable = "claude"),
    Replit(aiAgentEnvPrefixes = ["replit"]),
    GitHubCopilot(aiAgentEnvPrefixes = ["github-copilot", "github_copilot_vscode_agent"], executable = "copilot"),
    Kiro(aiAgentEnvPrefixes = ["kiro"], executable = "kiro-cli"),
    OpenClaw(aiAgentEnvPrefixes = ["openclaw"], executable = "openclaw"),
    Devin(aiAgentEnvPrefixes = ["devin"], executable = "devin"),

    /** An agent that we don't know specifically, but that identified itself via the `AI_AGENT` variable. */
    Other(["other"]),
}

private data class AgentEnvPrefixMapping(val agent: Agent, val aiAgentEnvNamePrefix: String)

private val agentEnvPrefixMappings = Agent.entries
    .flatMap { agent ->
        agent.aiAgentEnvPrefixes.map { AgentEnvPrefixMapping(agent, it) }
    }
    .sortedByDescending { it.aiAgentEnvNamePrefix.length }

/**
 * Returns the agent that declared itself with the given [AI_AGENT][aiAgentEnvValue] value,
 * or [Agent.Other] if unknown.
 */
internal fun agentByDeclaredName(aiAgentEnvValue: String): Agent {
    val sanitizedValue = aiAgentEnvValue.lowercase()
    return agentEnvPrefixMappings.firstOrNull { sanitizedValue.startsWith(it.aiAgentEnvNamePrefix) }?.agent
        ?: Agent.Other
}

/**
 * Returns the known agent whose markers are present in the environment, or null if there are none.
 *
 * Keep in sync with [agents.json](https://github.com/vercel/detect-agent/blob/main/agents.json).
 */
internal fun AgentDetectionEnvironment.detectAgentByMarkers(): Agent? = when {
    // Claude Code (and Claude Cowork, which also sets these markers)
    isAnySet("CLAUDECODE", "CLAUDE_CODE", "CLAUDE_CODE_CHILD_SESSION") -> Agent.ClaudeCode
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
    isAnySet("JUNIE_DATA", "JUNIE_SHIM_PATH", "MATTERHORN_TASK", "MATTERHORN_SESSION_ID") -> Agent.Junie
    // Pi has no dedicated variable, but it prepends its '.pi/agent' directory to the PATH
    getEnv("PATH").orEmpty().contains(PiAgentDirectoryInPath) -> Agent.Pi
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
internal fun AgentDetectionEnvironment.isAnySet(vararg names: String): Boolean =
    names.any { !getEnv(it).isNullOrEmpty() }
