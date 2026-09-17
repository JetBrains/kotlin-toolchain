/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.parameters.groups.provideDelegate
import org.jetbrains.amper.cli.context.GlobalCliContext
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.ProjectLayoutOptions
import org.jetbrains.amper.cli.userReadableError
import java.nio.file.Path

/**
 * An [AmperSubcommand] that can only be run in an Amper project.
 *
 * This parent command automatically finds a project and setups up project-local logging and telemetry.
 * It automatically fails if it's not run in the context of a project.
 */
internal abstract class AmperProjectAwareCommand(name: String) : AmperSubcommand(name) {

    protected val layoutOptions by ProjectLayoutOptions()

    final override suspend fun run() {
        when (val cliContext = findCliContext(layoutOptions)) {
            is ProjectCliContext -> {
                setProjectSpecificState(cliContext)
                try {
                    run(cliContext)
                } catch (e: Exception) {
                    throw LogsDirAwareInternalError(logsDir = cliContext.currentLogsRoot.path, cause = e)
                }
            }
            is GlobalCliContext -> userReadableError(
                "No Kotlin project found in the current directory or above. " +
                        "Make sure you have a project file or a module file at the root of your project, or specify " +
                        "`${ProjectLayoutOptions.PROJECT_DIR_OPTION_NAME}` explicitly to run apps from a project " +
                        "located elsewhere."
            )
        }
    }

    abstract suspend fun run(cliContext: ProjectCliContext)
}

/**
 * An exception that wraps any exception thrown during the execution of the command (internal error) to add information
 * about the logs directory, so we can ask users to upload it.
 */
class LogsDirAwareInternalError(val logsDir: Path, override val cause: Throwable) : Exception(cause)
