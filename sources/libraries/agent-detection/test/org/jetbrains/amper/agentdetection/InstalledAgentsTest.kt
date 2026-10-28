/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.agentdetection

import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

class InstalledAgentsTest {

    private val binDir = Path("/usr/local/bin")
    private val otherBinDir = Path("/opt/tools/bin")
    private val path = listOf(binDir, otherBinDir).joinToString(File.pathSeparator)

    private fun detectInstalled(
        vararg existingFiles: Path,
        env: Map<String, String> = mapOf("PATH" to path),
    ): Set<Agent> = detectInstalledAgents(FakeEnvironment(env = env, existingFiles = existingFiles.toSet()))

    @Test
    fun `nothing is installed`() {
        assertEquals([], detectInstalled())
        assertEquals([], detectInstalled(env = emptyMap()))
    }

    @Test
    fun `agents are detected from their executables on the PATH`() {
        assertEquals([Agent.ClaudeCode], detectInstalled(binDir / "claude"))
        assertEquals([Agent.Codex], detectInstalled(otherBinDir / "codex"))
        assertEquals([Agent.Antigravity], detectInstalled(binDir / "antigravity"))
        assertEquals([Agent.Junie], detectInstalled(binDir / "junie"))
        assertEquals([Agent.Pi], detectInstalled(binDir / "pi"))
        assertEquals([Agent.OpenCode], detectInstalled(binDir / "opencode"))
    }

    @Test
    fun `several installed agents are all detected`() {
        assertEquals(
            [Agent.ClaudeCode, Agent.Codex, Agent.Junie],
            detectInstalled(binDir / "claude", otherBinDir / "codex", binDir / "junie"),
        )
    }

    @Test
    fun `executables outside of the PATH are ignored`() {
        assertEquals([], detectInstalled(Path("/somewhere/else/claude")))
        assertEquals([], detectInstalled(binDir / "claude", env = emptyMap()))
    }

    @Test
    fun `executables are found with the PATHEXT extensions`() {
        val env = mapOf("PATH" to path, "PATHEXT" to ".COM;.EXE;.CMD")
        assertEquals([Agent.ClaudeCode], detectInstalled(binDir / "claude.exe", env = env))
        assertEquals([Agent.Codex], detectInstalled(binDir / "codex.cmd", env = env))
        assertEquals([], detectInstalled(binDir / "claude.exe"))
    }

    @Test
    fun `installed agents are not detected when detection is disabled`() {
        val env = mapOf("PATH" to path, "KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED" to "false")
        assertEquals([], detectInstalled(binDir / "claude", otherBinDir / "codex", env = env))
    }
}
