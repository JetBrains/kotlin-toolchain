/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.getModuleByName
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.terminal.promptBoolean
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.aomBuilder.defaultMavenRepositories
import org.jetbrains.amper.stdlib.collections.mapToSet
import org.jetbrains.amper.tasks.getModuleDependencies
import kotlin.collections.asSequence

internal class PublishCommand : AmperModelAwareCommand(name = "publish") {

    private val moduleNames by moduleOption(
        help = "The module to publish. If unspecified, the `publish` command publishes all modules with enabled " +
                "publication. This option can be repeated to publish multiple modules.",
    ).multiple().unique()

    private val repositoryId by argument(
        "repository-id",
        help = "The ID of the Maven repository to publish to, as specified in the repositories list. " +
                "Default repositories have the following IDs: ${defaultMavenRepositories.map { "`${it.id}`" }}."
    )

    private val transitive by option(
        "--transitive",
        help = "When enabled, the command also publishes modules that are local dependencies of the modules " +
                "specified with `-m`/`--module`. This option has no effect if `-m`/`--module` is not specified, " +
                "because all publishable modules are published anyway in that case."
    ).nullableFlag("--non-transitive")

    override fun help(context: Context): String = "Publish modules to a repository"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        val modulesToPublish = selectModulesToPublish(model, cliContext)

        withBackend(cliContext, model) { backend ->
            backend.publish(
                modules = modulesToPublish.distinctBy { it.userReadableName },
                repositoryId = repositoryId,
            )
        }
        printSuccessfulCommandConclusion("Publication successful")
    }

    private fun selectModulesToPublish(model: Model, cliContext: ProjectCliContext): List<AmperModule> {
        if (moduleNames.isEmpty()) {
            return model.modules
        }
        val explicitModules = moduleNames.map { model.getModuleByName(it) }
        if (transitive == false) {
            return explicitModules
        }
        val transitiveModules = context(cliContext) { explicitModules.transitiveClosure() }
        if (transitive == true) {
            return transitiveModules
        }
        val extraModuleNames = transitiveModules.mapToSet { it.userReadableName } - moduleNames
        return if (extraModuleNames.isEmpty()) {
            explicitModules
        } else {
            val shouldPublishTransitives = askForTransitivityOrFail(extraModuleNames)
            if (shouldPublishTransitives) {
                transitiveModules
            } else {
                explicitModules
            }
        }
    }

    private fun askForTransitivityOrFail(extraModuleNames: Set<String>): Boolean {
        val message = "The selected modules have dependencies on ${extraModuleNames.size} other " +
                "local modules:\n${extraModuleNames.joinToString("\n") { " - $it" }}"
        if (!terminal.terminalInfo.interactive) {
            userReadableError {
                appendLine(message)
                appendLine("Please pass the --transitive or --non-transitive flag to decide whether they should be " +
                        "published as well.")
            }
        }
        terminal.println(message)
        val response = terminal.promptBoolean("Do you want to publish these modules as well?", default = true)
        return response ?: throw PrintMessage("No option selected, publication canceled")
    }
}

context(cliContext: ProjectCliContext)
private fun Collection<AmperModule>.transitiveClosure(): List<AmperModule> =
    asSequence()
        .flatMap { it.transitiveClosure() }
        .distinctBy { it.userReadableName }
        .toList()

context(cliContext: ProjectCliContext)
private fun AmperModule.transitiveClosure(): Sequence<AmperModule> = sequence {
    yield(this@transitiveClosure)
    leafPlatforms.forEach { platform ->
        yieldAll(getModuleDependencies(isTest = false, platform = platform, dependencyReason = ResolutionScope.COMPILE))
        yieldAll(getModuleDependencies(isTest = false, platform = platform, dependencyReason = ResolutionScope.RUNTIME))
    }
}.distinctBy { it.userReadableName }
