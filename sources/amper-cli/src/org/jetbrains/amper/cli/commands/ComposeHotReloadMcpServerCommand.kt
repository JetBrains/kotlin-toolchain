/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.ProjectLayoutOptions
import org.jetbrains.amper.cli.project.preparePluginsAndReadModel
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.frontend.getComposeHotReloadVersion
import org.jetbrains.amper.jvm.getDefaultJdk
import org.jetbrains.amper.processes.runProcessWithInheritedIO
import org.jetbrains.amper.processes.withJavaArgFile
import org.jetbrains.amper.run.ToolingArtifactsDownloader
import org.jetbrains.amper.tasks.compose.isComposeEnabledFor
import org.jetbrains.amper.tasks.jvm.JvmHotRunTask
import java.io.File
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString

private val MinimalHotReloadVersionWithMcpSupport = ComparableVersion("1.2.0-beta01")

internal class ComposeHotReloadMcpServerCommand : AmperProjectAwareCommand(name = "compose-hot-reload-mcp-server") {

    override fun help(context: Context): String = """
            Start an MCP server for AI agent integration with the running Compose Hot Reload application.
            
            The following mcp.json config is possible:
            ```json
            {
                "mcpServers": {
                    "Compose Hot Reload": {
                        "command": "./kotlin",
                        "args": [
                            "compose-hot-reload-mcp-server"
                        ]
                    }
                }
            }
            ```
            
            NOTE: Changing the build directory using the `${ProjectLayoutOptions.BUILD_DIR_OPTION_NAME}` option
             can prevent hot-reload session discovery and connection.
            If you need to customize the build directory,
             make sure that the `run ${RunCommand.COMPOSE_HOT_RELOAD_OPTION_NAME}` command 
             has the same build directory specified as well.
        """.trimIndent()

    override suspend fun run(cliContext: ProjectCliContext) {
        val model = cliContext.preparePluginsAndReadModel()

        val hotReloadVersion = model.modules
            .filter { isComposeEnabledFor(it) }
            .mapNotNull { getComposeHotReloadVersion(it) }
            .maxOfOrNull { ComparableVersion(it) }
            // We have a diagnostic that reports if there are multiple different versions across the project
            ?: userReadableError("No modules supporting Compose Hot Reload detected")

        if (hotReloadVersion < MinimalHotReloadVersionWithMcpSupport) {
            userReadableError("Version '$hotReloadVersion' doesn't support MCP. " +
                    "Minimal version with MCP support is '$MinimalHotReloadVersionWithMcpSupport'")
        }

        val jdk = context(cliContext.problemReporter) {
            cliContext.jdkProvider.getDefaultJdk()
        }

        val downloader = ToolingArtifactsDownloader(
            userCacheRoot = cliContext.userCacheRoot,
            incrementalCache = cliContext.incrementalCache,
        )
        val classpath = context(cliContext.problemReporter) {
            downloader.downloadHotReloadMcp(hotReloadVersion.toString())
        }

        val pidFile = JvmHotRunTask.pidFilePath(cliContext.buildOutputRoot)
        val javaArgs = [
            "-cp",
            classpath.joinToString(File.pathSeparator) { it.absolutePathString() },
            "-Dcompose.reload.pidFile=${pidFile.absolutePathString()}",
            "org.jetbrains.compose.reload.mcp.ComposeHotReloadMcp",
        ]
        val result = withJavaArgFile(cliContext.projectTempRoot, javaArgs) { argFile ->
            runProcessWithInheritedIO(
                command = listOf(jdk.javaExecutable.pathString, "@${argFile.pathString}"),
                onStart = { pid ->
                    logger.info("Started MCP server with the pid: $pid")
                }
            )
        }
        if (result.exitCode != 0) {
            userReadableError("MCP server exited with code ${result.exitCode}", exitCode = result.exitCode)
        }
    }
}
