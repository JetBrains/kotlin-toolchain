/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class McpConfigsTest {
    @Test
    fun `hot reload server runs the POSIX wrapper on non-Windows`() {
        val configs = generateMcpConfigFiles(setOf(AiAgent.CLAUDE, AiAgent.CODEX), hasJvmComposeApp = true, windows = false)

        assertContains(configs.getValue(".mcp.json"), "\"command\": \"./kotlin\"")
        assertContains(configs.getValue(".codex/config.toml"), "command = \"./kotlin\"")
    }

    @Test
    fun `hot reload server runs the batch wrapper through cmd on Windows`() {
        val configs = generateMcpConfigFiles(setOf(AiAgent.CLAUDE, AiAgent.CODEX), hasJvmComposeApp = true, windows = true)

        assertContains(configs.getValue(".mcp.json"), "\"command\": \"cmd\"")
        assertContains(configs.getValue(".mcp.json"), "\"kotlin.bat\"")
        assertContains(configs.getValue(".codex/config.toml"), "command = \"cmd\"")
        assertFalse("./kotlin" in configs.getValue(".mcp.json"))
    }
}
