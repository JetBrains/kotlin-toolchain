/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.context.AmperProjectRoot
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.singleLeafFragment
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.jdkSettings
import org.jetbrains.amper.frontend.schema.JvmDistribution
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jdk.provisioning.JdkProvisioningCriteria
import org.jetbrains.amper.jdk.provisioning.orElse
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.run.ToolingArtifactsDownloader
import org.jetbrains.amper.tasks.ComposeHotReloadSettings
import org.jetbrains.amper.tasks.JvmMainRunSettings
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.pathString

class JvmHotRunTask(
    taskName: TaskName,
    module: AmperModule,
    userCacheRoot: AmperUserCacheRoot,
    projectRoot: AmperProjectRoot,
    tempRoot: AmperProjectTempRoot,
    terminal: Terminal,
    runSettings: JvmMainRunSettings,
    incrementalCache: IncrementalCache,
    jdkProvider: JdkProvider,
    processRunner: ProcessRunner,
    private val buildOutputRoot: AmperBuildOutputRoot,
    private val hotReloadSettings: ComposeHotReloadSettings,
    private val toolingArtifactsDownloader: ToolingArtifactsDownloader = ToolingArtifactsDownloader(
        userCacheRoot,
        incrementalCache
    ),
) : AbstractJvmRunTask(
    taskName,
    module,
    userCacheRoot,
    projectRoot,
    tempRoot,
    terminal,
    runSettings,
    incrementalCache,
    jdkProvider,
    processRunner,
) {
    override val buildType: BuildType
        get() = BuildType.Debug

    private val composeSettingsJvm by lazy {
        // Compose settings are platform-agnostic
        module.fragments
            .filter { !it.isTest }
            .filter { it.platforms == setOf(Platform.JVM) }
            .singleLeafFragment()
            .settings
            .compose
    }

    context(_: ProblemReporter)
    override suspend fun getJvmArgs(dependenciesResult: List<TaskResult>): List<String> {
        val agentClasspath = toolingArtifactsDownloader.downloadHotReloadAgent(
            hotReloadVersion = composeSettingsJvm.experimental.hotReload.version,
        )
        val agent = agentClasspath.singleOrNull { it.name.startsWith("hot-reload-agent") }
                ?: error("Can't find hot-reload-agent in agent classpath:\n${agentClasspath.joinToString("\n")}")

        val devToolsClasspath = toolingArtifactsDownloader.downloadDevTools(
            hotReloadVersion = composeSettingsJvm.experimental.hotReload.version,
            composeVersion = composeSettingsJvm.version,
        )

        val orchestrationPort = hotReloadSettings.orchestrationPort.await()

        val amperJvmArgs = [
            "-ea",
            "-XX:+AllowEnhancedClassRedefinition",
            "-javaagent:${agent.pathString}",
            "-Dcompose.reload.devToolsClasspath=${devToolsClasspath.joinToString(File.pathSeparator)}",
            "-Dcompose.reload.devToolsEnabled=true",
            "-Dcompose.reload.devToolsTransparencyEnabled=true",
            "-Dcompose.reload.dirtyResolveDepthLimit=5",
            "-Dcompose.reload.virtualMethodResolveEnabled=true",
            "-Dcompose.reload.orchestration.port=$orchestrationPort",
            "-Dcompose.reload.pidFile=${pidFilePath(buildOutputRoot).absolutePathString()}",
        ]

        return amperJvmArgs + runSettings.userJvmArgs
    }

    context(_: ProblemReporter)
    override suspend fun getClasspath(dependenciesResult: List<TaskResult>): List<Path> {
        val classpath = super.getClasspath(dependenciesResult)
        val agentClasspath = toolingArtifactsDownloader.downloadHotReloadAgent(
            hotReloadVersion = composeSettingsJvm.experimental.hotReload.version,
        )
        val agent = agentClasspath.singleOrNull { it.name.startsWith("hot-reload-agent") }
            ?: error("Can't find hot-reload-agent in agent classpath:\n${agentClasspath.joinToString("\n")}")
        val filteredAgentClasspath = agentClasspath.filter { !it.pathString.contains(agent.pathString) }

        return buildList {
            addAll(classpath)
            addAll(filteredAgentClasspath)

            val hasComposeDesktop = classpath.any { it.pathString.contains("org/jetbrains/compose/desktop") }
            if (!hasComposeDesktop) {
                addAll(toolingArtifactsDownloader.downloadComposeDesktop(composeSettingsJvm.version))
            }
        }
    }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun getJdk(): Jdk = operationEventScope(
        moniker = "provisioning Hot Reload runtime",
        sink = executionContext.eventSink,
    ) {
        return jdkProvider.getJdk(
            criteria = JdkProvisioningCriteria(
                majorVersion = module.jdkSettings.version, // we want a JBR in the same version as the user's JDK
                distributions = listOf(JvmDistribution.JetBrainsRuntime), // the JBR is necessary to run Compose Hot Reload
            ),
            selectionMode = module.jdkSettings.selectionMode,
        ).orElse { errorMessage ->
            userReadableError("Compose Hot Reload requires a JetBrains Runtime (JBR) to run, but the Kotlin Toolchain could not " +
                    "provision one that matches the configured JDK version: $errorMessage")
        }
    }

    companion object {
        fun pidFilePath(buildOutputRoot: AmperBuildOutputRoot): Path {
            return buildOutputRoot.path / "hot-reload-app.pid"
        }
    }
}
