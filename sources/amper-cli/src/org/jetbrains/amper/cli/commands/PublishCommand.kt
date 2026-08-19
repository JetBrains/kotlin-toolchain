/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.getModuleByName
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.aomBuilder.defaultMavenRepositories
import org.jetbrains.amper.tasks.getModuleDependencies
import kotlin.collections.asSequence

internal class PublishCommand : AmperModelAwareCommand(name = "publish") {

    private val modules by moduleOption(
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
    ).flag(default = false)

    override fun help(context: Context): String = "Publish modules to a repository"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        val explicitModules = modules.map { model.getModuleByName(it) }
        val modulesToPublish = when {
            explicitModules.isEmpty() -> model.modules
            transitive -> context(cliContext) { explicitModules.transitiveClosure() }
            else -> explicitModules
        }

        withBackend(cliContext, model) { backend ->
            backend.publish(
                modules = modulesToPublish.distinctBy { it.userReadableName },
                repositoryId = repositoryId,
            )
        }
        printSuccessfulCommandConclusion("Publication successful")
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
    leafPlatforms.forEach { platform ->
        yieldAll(getModuleDependencies(isTest = false, platform = platform, dependencyReason = ResolutionScope.COMPILE))
        yieldAll(getModuleDependencies(isTest = false, platform = platform, dependencyReason = ResolutionScope.RUNTIME))
    }
}.distinctBy { it.userReadableName }
