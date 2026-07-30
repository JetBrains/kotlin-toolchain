/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.ChecksCompletionCandidates
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.engine.TaskExecutor
import org.jetbrains.amper.frontend.Model

internal class CheckCommand : AmperModelAwareCommand(name = "check") {

    private val modules by moduleOption(
        help = "The specific module to run checks in (run the `show modules` command to get the modules list). " +
                "This option can be repeated to run checks in several modules."
    ).multiple().unique()

    private val skip by option("-s", "--skip",
        help = "Skip the specified check. This option can be repeated to skip several checks."
    ).multiple().unique()

    private val checkNames by argument(
        name = "checks",
        help = "The names of the checks to run. Run the `show checks` command to get all the available checks. " +
                "If not specified, all checks are run.",
        completionCandidates = ChecksCompletionCandidates,
    ).multiple()

    // TODO: arguments for tests, like buildType, filter, etc.

    override fun help(context: Context): String = "Run checks in the project"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        if (checkNames.isNotEmpty() && skip.isNotEmpty()) {
            userReadableError("Cannot use both positional check names and --skip at the same time")
        }

        withBackend(
            cliContext = cliContext,
            model = model,
            taskExecutionMode = TaskExecutor.Mode.GREEDY,
        ) { backend ->
            backend.check(
                modules = modules.takeIf { it.isNotEmpty() },
                checkNames = checkNames.takeIf { it.isNotEmpty() }?.toSet(),
                skip = skip,
            )
        }
        printSuccessfulCommandConclusion("Check successful")
    }
}
