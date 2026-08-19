/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.unique
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.aomBuilder.defaultMavenRepositories

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

    override fun help(context: Context): String = "Publish modules to a repository"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        withBackend(cliContext, model) { backend ->
            backend.publish(
                modules = modules.takeIf { it.isNotEmpty() },
                repositoryId = repositoryId,
            )
        }
        printSuccessfulCommandConclusion("Publication successful")
    }
}
