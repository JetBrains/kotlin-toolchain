/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

/**
 * An AI coding agent that can be configured.
 *
 * Each entry carries the CLI flag value, a human-readable display name as mandatory fields.
 */
enum class AiAgent(val cliValue: String, val displayName: String, internal val mcpFilePath: String?) {
    CLAUDE("claude", "Claude Code", ".mcp.json"),
    CODEX("codex", "Codex", ".codex/config.toml"),
    ANTIGRAVITY("antigravity", "Antigravity", ".agents/mcp_config.json"),
    JUNIE("junie", "Junie", ".junie/mcp/mcp.json"),
    PI("pi", "pi", ".pi/mcp.json"),
    OPENCODE("opencode", "OpenCode", ".opencode/opencode.json"),
    OTHER("other", "Other AI agents", null)
    ;

    companion object {
        /** Lookup table from [cliValue] to [AiAgent], for parsing CLI arguments. */
        val byCliValue: Map<String, AiAgent> = entries.associateBy { it.cliValue }
    }
}
