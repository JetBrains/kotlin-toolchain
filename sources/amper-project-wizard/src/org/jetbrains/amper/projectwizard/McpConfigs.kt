/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo

private sealed interface McpServer {
    val name: String

    data class Stdio(override val name: String, val command: String, val args: List<String>) : McpServer
    data class Remote(override val name: String, val url: String) : McpServer
}

/**
 * The hot-reload server is launched through the Kotlin CLI wrapper. Windows has no executable `kotlin` script, only
 * `kotlin.bat`, which MCP clients can't spawn directly, so it's run through `cmd`.
 */
private fun composeHotReloadServer(windows: Boolean) = if (windows) {
    McpServer.Stdio("compose-hot-reload", command = "cmd", args = ["/c", "kotlin.bat", "compose-hot-reload-mcp-server"])
} else {
    McpServer.Stdio("compose-hot-reload", command = "./kotlin", args = ["compose-hot-reload-mcp-server"])
}

private val KlibsServer = McpServer.Remote("klibs", url = "https://api.klibs.io/mcp")

/**
 * Generates MCP config file contents for the [agents] that were requested.
 *
 * @param hasJvmComposeApp whether the generated project has a JVM Compose app to hot-reload
 * @param windows whether the configs are generated for Windows, where the hot-reload server is launched via `kotlin.bat`
 *
 * [AiAgent.CLAUDE], [AiAgent.JUNIE], and [AiAgent.PI] all share the same JSON schema and receive identical
 * content. The root `.mcp.json` is only written when [AiAgent.CLAUDE] is selected; the other two write to
 * their own paths regardless of whether Claude is also selected.
 *
 * We don't use symlinks here because Windows requires a privileged process to create them. To clone the git repo with
 * symlinks git also must be set up with `git config core.symlinks true` which is disabled by default.
 *
 */
fun generateMcpConfigFiles(
    agents: Set<AiAgent>,
    hasJvmComposeApp: Boolean,
    windows: Boolean = SystemInfo.CurrentHost.family == OsFamily.Windows,
): Map<String, String> {
    if (agents.isEmpty()) return emptyMap()
    val servers = buildList {
        if (hasJvmComposeApp) add(composeHotReloadServer(windows))
        add(KlibsServer)
    }
    val sharedConfig by lazy { servers.claudeConfig() }
    return buildMap {
        for (agent in agents) {
            val content = when (agent) {
                AiAgent.CLAUDE, AiAgent.JUNIE, AiAgent.PI -> sharedConfig
                AiAgent.CODEX -> servers.codexConfig()
                AiAgent.ANTIGRAVITY -> servers.antigravityConfig()
                AiAgent.OPENCODE -> servers.openCodeConfig()
            }
            put(agent.mcpFilePath, content)
        }
    }
}

/**
 * The `.gitignore` entries for the MCP config files that [generateMcpConfigFiles] writes for the given [agents].
 */
fun mcpConfigGitIgnoreEntries(agents: Set<AiAgent>): List<String> = agents.map { "/${it.mcpFilePath}" }.distinct()

private fun List<McpServer>.claudeConfig(): String = jsonServers("mcpServers") { server ->
    when (server) {
        is McpServer.Stdio -> {
            put("command", server.command)
            putJsonArray("args") { server.args.forEach { add(it) } }
        }
        is McpServer.Remote -> {
            put("type", "http")
            put("url", server.url)
        }
    }
}

// Antigravity expects `serverUrl` for remote servers; `url` and `httpUrl` are not supported.
private fun List<McpServer>.antigravityConfig(): String = jsonServers("mcpServers") { server ->
    when (server) {
        is McpServer.Stdio -> {
            put("command", server.command)
            putJsonArray("args") { server.args.forEach { add(it) } }
        }
        is McpServer.Remote -> put("serverUrl", server.url)
    }
}

private fun List<McpServer>.openCodeConfig(): String =
    jsonServers("mcp", schema = "https://opencode.ai/config.json") { server ->
        when (server) {
            is McpServer.Stdio -> {
                put("type", "local")
                putJsonArray("command") { add(server.command); server.args.forEach { add(it) } }
                put("enabled", true)
            }
            is McpServer.Remote -> {
                put("type", "remote")
                put("url", server.url)
                put("enabled", true)
            }
        }
    }

private fun List<McpServer>.codexConfig(): String = joinToString("\n") { server ->
    when (server) {
        is McpServer.Stdio -> """
            [mcp_servers.${server.name}]
            command = ${server.command.quoted()}
            args = [${server.args.joinToString(", ") { it.quoted() }}]
        """.trimIndent()
        is McpServer.Remote -> """
            [mcp_servers.${server.name}]
            url = ${server.url.quoted()}
        """.trimIndent()
    } + "\n"
}

private val PrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private fun List<McpServer>.jsonServers(
    key: String,
    schema: String? = null,
    render: JsonObjectBuilder.(McpServer) -> Unit,
): String {
    val root = buildJsonObject {
        schema?.let { put("\$schema", it) }
        putJsonObject(key) {
            for (server in this@jsonServers) putJsonObject(server.name) { render(server) }
        }
    }
    return PrettyJson.encodeToString(JsonObject.serializer(), root) + "\n"
}

private fun String.quoted() = "\"$this\""
