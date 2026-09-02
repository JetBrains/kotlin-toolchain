/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.compose

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.test.processes.TestReporterProcessOutputListener
import org.jetbrains.amper.test.runTestWithMdc
import org.jetbrains.compose.reload.DelicateHotReloadApi
import org.jetbrains.compose.reload.core.getOrThrow
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationServer
import org.jetbrains.compose.reload.orchestration.asFlow
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateHotReloadApi::class)
class ComposeHotReloadMcpServerCommandTest : CliTestBase() {
    @Test
    fun `compose hot reload mcp server connects to the orchestration using the pid file`() = runTestWithMdc(
        timeout = 300.seconds,
    ) {
        val buildDir = tempRoot / "build"
        val liveLogs = Channel<String>(capacity = Channel.UNLIMITED)
        val mcpServer = launch {
            try {
                runCli(
                    projectDir = testProject("compose-hot-reload"),
                    "compose-hot-reload-mcp-server",
                    buildOutputRoot = buildDir,
                    outputListener = object : ProcessOutputListener {
                        override fun onStderrLine(line: String, pid: Long) = Unit
                        override fun onStdoutLine(line: String, pid: Long) {
                            check(liveLogs.trySend(line).isSuccess)
                        }
                    } + TestReporterProcessOutputListener("amper", testReporter)
                )
            } finally {
                liveLogs.close()
            }
        }

        val mcpPid = liveLogs.consumeAsFlow()
            .mapNotNull { McpStartedRegex.matchEntire(it.trim()) }
            .first().groupValues[1].toLong()

        repeat(3) {  // Verify that the mcp server repeatedly connects to every orchestration
            assertEquals(
                expected = mcpPid,
                actual = startServerAndAwaitConnection(
                    buildDir = buildDir,
                ).clientPid,
            )
        }

        mcpServer.cancel()
    }

    private suspend fun TestScope.startServerAndAwaitConnection(
        buildDir: Path,
    ): OrchestrationMessage.ClientConnected = async {
        val server = OrchestrationServer()
        try {
            server.start()
            server.bind()
            val port = server.port.await().getOrThrow()
            val pidFile = buildDir / "hot-reload-app.pid"

            val thisPid = ProcessHandle.current().pid()
            pidFile.createParentDirectories().writeText(
                """
                    orchestration.port=$port
                    pid=$thisPid
                """.trimIndent()
            )
            server.asFlow()
                .filterIsInstance<OrchestrationMessage.ClientConnected>()
                .first()
        } finally {
            server.stop()
        }
    }.await()
}

private val McpStartedRegex = """INFO\s+Started MCP server with the pid: (\d+)""".toRegex()