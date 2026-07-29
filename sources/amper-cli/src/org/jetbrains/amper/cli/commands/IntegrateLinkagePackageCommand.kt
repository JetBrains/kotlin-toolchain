/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.parameters.options.option
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.getModuleByName
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.getTaskName

internal class IntegrateLinkagePackageCommand : AmperModelAwareCommand(name = COMMAND_NAME) {

    private val module by option("-m", "--module",
        help = "Name of module with the Xcode project that should be integrated with SwiftPM dependencies"
    )

    override val hiddenFromHelp: Boolean
        get() = true

    override suspend fun run(
        cliContext: ProjectCliContext,
        model: Model,
    ) {
        withBackend(cliContext, model = model) { backend ->
            val targetModule: AmperModule
            if (module != null) {
                targetModule = model.getModuleByName(module!!)
            } else {
                val appModules = model.modules.filter {
                    it.type == ProductType.IOS_APP
                }
                if (appModules.isEmpty()) {
                    userReadableError("Project has no iOS app modules")
                }
                if (appModules.size > 1) {
                    userReadableError("Please specify --module argument. Project has more than one iOS app module: ${appModules.joinToString(", ") { "'${it.userReadableName}'" }}")
                }
                targetModule = appModules.single()
            }

            backend.runTask(
                ModuleTaskTypes.IntegrateLinkagePackage.getTaskName(targetModule).id
            )
        }
    }

    companion object {
        const val COMMAND_NAME = "integrate-linkage-package"
    }
}