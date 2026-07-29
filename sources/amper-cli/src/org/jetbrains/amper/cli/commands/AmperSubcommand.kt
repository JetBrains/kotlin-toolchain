/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.terminal.success
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.context.AmperProjectRoot
import org.jetbrains.amper.cli.context.CliContext
import org.jetbrains.amper.cli.context.GlobalCliContext
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.context.findProjectContext
import org.jetbrains.amper.cli.createProblemReporterForCli
import org.jetbrains.amper.cli.logging.LoggingInitializer
import org.jetbrains.amper.cli.options.ProjectLayoutOptions
import org.jetbrains.amper.cli.telemetry.TelemetryEnvironment
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.wrapper.AmperWrapperData
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal abstract class AmperSubcommand(name: String) : SuspendingCliktCommand(name = name) {
    /**
     * The logger for this command.
     */
    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    /**
     * The common options that can be passed to the root command.
     */
    protected val commonOptions by requireObject<RootCommand.CommonOptions>()

    /**
     * Detects the environment in which the CLI is called, and returns a [CliContext] to represent it.
     */
    protected suspend fun findCliContext(
        layoutOptions: ProjectLayoutOptions? = null,
    ): CliContext = spanBuilder("Create CLI context").use {
        require(commandName.isNotBlank()) { "commandName should not be blank" }

        val problemReporter = createProblemReporterForCli(terminal, projectRoot = null)

        val projectContext = context(problemReporter) {
            findProjectContext(
                explicitProjectDir = layoutOptions?.explicitProjectDir,
                explicitBuildDir = layoutOptions?.explicitBuildDir,
            )
        }
        if (projectContext == null) {
            GlobalCliContext(
                commandName = commandName,
                userCacheRoot = commonOptions.sharedCachesRoot,
                terminal = terminal,
                problemReporter = problemReporter,
            )
        } else {
            ProjectCliContext(
                commandName = commandName,
                projectContext = projectContext,
                userCacheRoot = commonOptions.sharedCachesRoot,
                terminal = terminal,
                problemReporter = createProblemReporterForCli(
                    terminal = terminal,
                    projectRoot = projectContext.projectRoot.path,
                ),
            )
        }
    }

    /**
     * Sets some global state of the current CLI execution to the given project context.
     *
     * For example, this function sets the location of the logs directory for future logs and telemetry.
     */
    protected suspend fun setProjectSpecificState(projectCliContext: ProjectCliContext) {
        spanBuilder("Switch telemetry to project-local build directory").use {
            TelemetryEnvironment.setLogsRootDirectory(projectCliContext.currentLogsRoot)
        }
        spanBuilder("Setup file logging and monitoring").use {
            LoggingInitializer.setupFileLogging(projectCliContext.currentLogsRoot)
        }
        // checkWrapperVersionConsistency(projectCliContext.projectRoot)
    }

    private fun checkWrapperVersionConsistency(projectRoot: AmperProjectRoot) {
        val projectWrapperVersion = AmperWrapperData.parseFromProjectRoot(projectRoot.path)?.version ?: return

        if (projectWrapperVersion != AmperBuild.mavenVersion) {
            logger.warn(
                "Running Kotlin CLI version (${AmperBuild.mavenVersion}) is different from " +
                        "the project wrapper version (${projectWrapperVersion}). " +
                        "NOTE: If you are using the global wrapper, make sure you run it inside the project directory."
            )
        }
    }

    /**
     * Prints a message to the console with the 'success' style, and conclusion formatting.
     */
    fun printSuccessfulCommandConclusion(message: String) {
        terminal.success(message)
    }
}
