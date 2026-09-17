/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.context

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.AmperVersion
import org.jetbrains.amper.compose.reload.HotReloadProjectContext
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.util.DateTimeFormatForFilenames
import org.jetbrains.amper.util.nowInDefaultTimezone
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.jvm.optionals.getOrNull

interface ProjectCliContext : CliContext, HotReloadProjectContext {

    val projectContext: AmperProjectContext

    val projectRoot: AmperProjectRoot

    val buildOutputRoot: AmperBuildOutputRoot

    val projectTempRoot: AmperProjectTempRoot

    /**
     * The root directory containing all logs for all Kotlin CLI executions in the current project.
     */
    val projectLogsRoot: AmperProjectLogsRoot

    /**
     * The logs directory for the current Kotlin CLI execution.
     */
    val currentLogsRoot: AmperBuildLogsRoot

    companion object {
        /**
         * An absolute path to the wrapper script that the process currently runs under.
         */
        val wrapperScriptPath: Path by lazy {
            System.getenv("KOTLIN_CLI_WRAPPER_PATH")?.takeIf { it.isNotBlank() }?.let(::Path)
                ?: error("Missing `KOTLIN_CLI_WRAPPER_PATH` env var. Is your Kotlin Toolchain distribution intact?")
        }
    }
}

/**
 * Creates the [ProjectCliContext] instance using the supplied info.
 */
fun ProjectCliContext(
    commandName: String,
    userCacheRoot: AmperUserCacheRoot,
    terminal: Terminal,
    problemReporter: ProblemReporter,
    projectContext: AmperProjectContext,
): ProjectCliContext = ProjectCliContextImpl(
    commandName = commandName,
    userCacheRoot = userCacheRoot,
    terminal = terminal,
    problemReporter = problemReporter,
    projectContext = projectContext,
)

/**
 * Copies the given context with the new project structure.
 *
 * @throws IllegalArgumentException when the *root* and/or *build* directories of the new context differ from the ones
 *  in the old one.
 */
fun ProjectCliContext.copyWithNewProjectContext(
    projectContext: AmperProjectContext,
): ProjectCliContext = ReloadedProjectCliContext(
    baseContext = when (this) {
        is ReloadedProjectCliContext -> baseContext  // Avoid repeated re-wrappings
        else -> this
    },
    projectContext = projectContext,
)

private class ReloadedProjectCliContext(
    val baseContext: ProjectCliContext,
    override val projectContext: AmperProjectContext,
) : ProjectCliContext by baseContext {
    init {
        require(baseContext.projectContext.projectRoot.path == projectContext.projectRoot.path) {
            "Reinitializing project context error: projectRoot has to be the same as in the old context. " +
                    "Expected: ${baseContext.projectContext.projectRoot.path}, got: ${projectContext.projectRoot.path}"
        }
        require(baseContext.projectContext.projectBuildDir == projectContext.projectBuildDir) {
            "Reinitializing project context error: projectBuildDir has to be the same as in the old context. " +
                    "Expected: ${baseContext.projectContext.projectBuildDir}, got: ${projectContext.projectBuildDir}"
        }
    }
}

private class ProjectCliContextImpl(
    override val commandName: String,
    override val userCacheRoot: AmperUserCacheRoot,
    override val terminal: Terminal,
    override val problemReporter: ProblemReporter,
    override val projectContext: AmperProjectContext,
) : CliContextBase(), ProjectCliContext {

    override val projectRoot: AmperProjectRoot = AmperProjectRoot(projectContext.projectRoot.path)

    override val buildOutputRoot: AmperBuildOutputRoot by lazy {
        AmperBuildOutputRoot(projectContext.projectBuildDir.createDirectories())
    }

    override val projectTempRoot: AmperProjectTempRoot by lazy {
        AmperProjectTempRoot((projectContext.projectBuildDir / "temp").createDirectories())
    }

    override val projectLogsRoot: AmperProjectLogsRoot by lazy {
        AmperProjectLogsRoot((projectContext.projectBuildDir / "logs").createDirectories())
    }

    override val currentLogsRoot: AmperBuildLogsRoot by lazy {
        val currentTimestamp = LocalDateTime.nowInDefaultTimezone().format(DateTimeFormatForFilenames)
        val currentProcess = ProcessHandle.current()

        val pid = currentProcess.pid() // avoid clashes with concurrent Amper processes

        /*
        On linux/macOS, the wrapper script uses 'exec java' to start Amper, which replaces the process and uses the
         same PID.

        On Windows, there is no equivalent, so child processes are created.
        That happens twice: once the wrapper calls the launcher and once more when the launcher calls java.exe.
        The "wrapper -> launcher" call is done via the busybox*.exe binary,
         which then does an `exec` for java.exe. But in Windowns busybox port `exec` still spawns the child process
         as there is no other way.

        Callers are only aware of the wrapper process ID,
         so to allow the automated discovery of the logs dir, we also record the parent process ID for Windows users.
        */
        val grandParentPid = currentProcess.parent().getOrNull()?.parent()?.getOrNull()?.pid()

        val currentLogsPath = projectLogsRoot.path.resolve("amper_${currentTimestamp}_${pid}-${grandParentPid}_$commandName")
        AmperBuildLogsRoot(currentLogsPath.createDirectories())
    }

    /**
     * The incremental cache for the current project.
     */
    override val incrementalCache: IncrementalCache by lazy {
        IncrementalCache(
            stateRoot = buildOutputRoot.path.resolve("incremental.state"),
            codeVersion = AmperVersion.codeIdentifier,
            openTelemetry = openTelemetry,
        )
    }

    override val processRunner: ProcessRunner by lazy {
        ProcessRunner(telemetryDir = currentLogsRoot.telemetryPath)
    }
}
