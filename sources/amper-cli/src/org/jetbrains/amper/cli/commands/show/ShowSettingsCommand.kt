/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands.show

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import org.jetbrains.amper.cli.commands.AmperModelAwareCommand
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.AllModulesOptionName
import org.jetbrains.amper.cli.options.moduleFilter
import org.jetbrains.amper.cli.options.selectModules
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.serialization.YamlTheme
import org.jetbrains.amper.frontend.serialization.serializeAsAmperYaml

internal class ShowSettingsCommand : AmperModelAwareCommand(name = "settings") {

    private val moduleFilter by moduleFilter(
        moduleOptionHelp = """
            The module to show the settings of (run the `show modules` command to get the modules list).
            This option can be repeated to show settings for several modules.
            If unspecified, you will be prompted to choose a module.

            See also `$AllModulesOptionName` if you want to show settings for all modules in the project.
        """.trimIndent(),
        allModulesOptionHelp = "Show settings for all modules.",
    )

    override fun help(context: Context): String = "Print the effective settings of each module"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        val nModules = model.modules.size
        val selectedModules = moduleFilter.selectModules(model.modules)
        val formattedSettings = selectedModules.joinToString("\n\n") { module ->
            formatModuleSettings(module, showModuleName = nModules > 1)
        }
        terminal.println(formattedSettings)
    }

    private fun formatModuleSettings(module: AmperModule, showModuleName: Boolean): String {
        val moduleHeader = if (showModuleName) terminal.theme.info("Module: ${module.userReadableName}\n\n") else ""
        val moduleSettings = module.fragments
            .distinctBy { it.platforms }
            .joinToString("\n\n") { fragment ->
                formatFragmentSettings(fragment = fragment, productType = module.type)
            }
        return "$moduleHeader$moduleSettings"
    }

    private fun formatFragmentSettings(fragment: Fragment, productType: ProductType): String {
        val settingsNodeName = if (fragment.platforms.isNotEmpty()) {
            "settings@${fragment.platforms.joinToString("+") { it.pretty }}:"
        } else {
            "settings:"
        }
        val yamlLikeEffectiveSettings = fragment.settings.serializeAsAmperYaml(
            productType = productType,
            contexts = fragment.platforms,
            theme = object : YamlTheme {
                override fun colorize(text: String, type: YamlTheme.SpecialElementType): String = when (type) {
                    YamlTheme.SpecialElementType.Comment -> terminal.theme.muted(text)
                    YamlTheme.SpecialElementType.PropertyName -> terminal.theme.info(text)
                }
            }
        )
        return "${terminal.theme.info(settingsNodeName)}\n${yamlLikeEffectiveSettings.trim().prependIndent("  ")}"
    }
}
