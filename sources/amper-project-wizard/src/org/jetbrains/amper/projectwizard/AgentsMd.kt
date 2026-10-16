/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

/** The path of the file with instructions for AI coding agents, relative to the project root. */
const val AgentsMdPath = "AGENTS.md"

/**
 * The content of the `AGENTS.md` file of a generated project.
 *
 * @param hotReloadable whether the project has a Compose Hot Reload-capable app, in which case the UI development
 * instructions are included.
 */
fun agentsMd(hotReloadable: Boolean): String = buildString {
    append(AgentsMdIntro)
    if (hotReloadable) {
        append("\n")
        append(AgentsMdUiDevelopment)
    }
}

private val AgentsMdIntro = """
    # Instructions for Coding Agents

    This project uses the [Kotlin Toolchain](https://kotlin-toolchain.org/) (a dedicated build tool for Kotlin) through
    the `./kotlin` wrapper.
    Run `./kotlin --help` to see the available commands, or `./kotlin <command> --help` to see command-specific options.
""".trimIndent() + "\n"

private val AgentsMdUiDevelopment = """
    ## UI Development

    Use the Compose Hot Reload MCP server to develop and validate UI changes. It significantly shortens the feedback loop
    and is much faster than running the app in an emulator or simulator.
    It provides tools for interacting with the app, capturing screenshots, and inspecting the composable semantic tree.
    Run `./kotlin compose-hot-reload-mcp-server --help` to learn how to configure the MCP server.
    Prefer headless mode when available.
    To use MCP server make sure that the app is running in `--compose-hot-reload` mode. Use `./kotlin run --help` to learn
    more.

    Before making any changes in the UI code, start the app first in the background
    (`./kotlin run -m <compose-hot-reloadable-module> --compose-hot-reload`), then immediately call the MCP `status` tool.
    Repeat `status` until it reports `connected: true` (the first build can take minutes). `status` is the only readiness
    signal: do NOT tail/grep the run log or write sleep loops. If startup fails, check `lastError` in `status` and the MCP
    `get_logs` tool.

    Once connected, edit the UI and apply changes with MCP `reload` (or `await_reload` if `buildContinuous: true`).
""".trimIndent() + "\n"
