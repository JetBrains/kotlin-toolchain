/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.unique
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.CustomCommandsCompletionCandidates
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.engine.TaskExecutor
import org.jetbrains.amper.frontend.Model

internal class DoCustomCommand : AmperModelAwareCommand(name = "do") {

    private val modules by moduleOption(
        help = "The specific module to run the custom command in " +
                "(run the `show modules` command to get the modules list). " +
                "This option can be repeated to run the command in several modules. " +
                "By default the command is run in all the modules where it is present."
    ).multiple().unique()

    private val customCommandName by argument(
        name = "command",
        help = "The (qualified) name of the custom command to run. " +
                "Run the `show commands` command to get the custom commands list.",
        completionCandidates = CustomCommandsCompletionCandidates,
    )

    override fun help(context: Context): String = "Run a custom command"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        withBackend(
            cliContext = cliContext,
            model = model,
            taskExecutionMode = TaskExecutor.Mode.GREEDY,
        ) { backend ->
            backend.doCustomCommand(
                modules = modules.takeIf { it.isNotEmpty() },
                commandName = customCommandName,
            )
        }
        printSuccessfulCommandConclusion("$customCommandName successful")
    }
}
