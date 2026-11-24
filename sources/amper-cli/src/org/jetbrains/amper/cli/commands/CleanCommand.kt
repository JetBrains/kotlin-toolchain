/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.warning
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.ProjectLayoutOptions
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.widgets.withIndeterminateProgress
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

private class CleanScopeOptions : OptionGroup("Clean scope options") {
    val shared by option(
        "--shared",
        help = "Remove the caches that are shared between projects instead of the project's build output",
    ).flag()

    val all by option(
        "--all",
        help = "Remove both the project's build output and the shared caches",
    ).flag()
}

// Not an AmperProjectAwareCommand because we don't want to generate logs and telemetry in the build folder.
// This is sort of a global command that cleans the project where it's called.
internal class CleanCommand : AmperSubcommand(name = "clean") {

    private val layoutOptions by ProjectLayoutOptions()
    private val cleanScopeOptions by CleanScopeOptions()

    override fun help(context: Context): String =
        "Remove the output and caches produced by other commands. By default, affects only the project's cache."

    override suspend fun run() {
        val (shared, all) = cleanScopeOptions
        if (shared && all) {
            userReadableError("The '--shared' and '--all' options cannot be used together")
        }

        if (!shared) cleanProjectOutput(reportMissingProjectAsError = !all)
        if (shared || all) cleanSharedCaches()
        printSuccessfulCommandConclusion("Clean successful")
    }

    private suspend fun cleanProjectOutput(reportMissingProjectAsError: Boolean) {
        val cliContext = findCliContext(layoutOptions)
        if (cliContext !is ProjectCliContext) {
            if (reportMissingProjectAsError) {
                userReadableError("No Kotlin project found, nothing to clean")
            }
            terminal.warning("No Kotlin project found, skipping the project's build output. Use '--shared' to only clean the shared caches")
            return
        }

        val buildOutputRoot = cliContext.buildOutputRoot.path
        if (buildOutputRoot.exists()) {
            terminal.withIndeterminateProgress("Deleting project build output and caches…") {
                buildOutputRoot.deleteRecursively()
            }
        }
    }

    private suspend fun cleanSharedCaches() {
        val sharedCachesRoot = commonOptions.sharedCachesRoot.path
        if (sharedCachesRoot.exists()) {
            terminal.withIndeterminateProgress("Deleting shared caches at $sharedCachesRoot…") {
                sharedCachesRoot.deleteRecursively()
            }
        }
    }
}
