/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands.show

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.transformAll
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.BorderType
import com.github.ajalt.mordant.table.table
import org.jetbrains.amper.cli.QualifiedName
import org.jetbrains.amper.cli.commands.AmperModelAwareCommand
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.filterByPluginId
import org.jetbrains.amper.cli.options.ModuleFilter
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.options.selectModules
import org.jetbrains.amper.frontend.Model

private enum class CustomCommandsListFormat(val cliName: String) {
    Plain("plain"),
    Table("table"),
}

internal class ShowCustomCommandsCommand : AmperModelAwareCommand(name = "commands") {

    private val modules by moduleOption(
        help = """
            The module to show the custom commands of (run the `show modules` command to get the modules list).
            This option can be repeated to show custom commands for several modules.
            If unspecified, custom commands for all modules are shown.
        """.trimIndent(),
    ).transformAll { if (it.isEmpty()) null else ModuleFilter.Names(it.toSet()) }

    private val pluginIdFilter by option(
        "--plugin",
        help = "Filter custom commands by plugin ID. If specified, only custom commands from the given plugin are shown."
    )

    private val format by option(
        "--format",
        help = """
            The format of the output. Available formats:
             - `plain`: plain list of *qualified* custom command names
             - `table`: formatted table with multiple columns
        """.trimIndent(),
    ).enum<CustomCommandsListFormat> { it.cliName }
        .default(CustomCommandsListFormat.Table, defaultForHelp = "table")

    override fun help(context: Context): String = "Print all available custom commands in the project"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        val selectedModules = (modules ?: ModuleFilter.All).selectModules(model.modules)

        val allCommands = selectedModules.flatMap { it.customCommandsFromPlugins }
            .map { QualifiedName(it.name, it.pluginId.value) }
            .distinct()

        val commandsToShow = allCommands.run {
            pluginIdFilter?.let { filterByPluginId(model, it) } ?: this
        }.sorted()

        if (commandsToShow.isEmpty()) {
            val noCommandsAtAll = allCommands.isEmpty()
            terminal.println(
                if (noCommandsAtAll) "No custom commands found in the project."
                else "No custom commands found for the given filter."
            )
            return
        }

        when (format) {
            CustomCommandsListFormat.Plain -> printPlain(commandsToShow)
            CustomCommandsListFormat.Table -> printTable(commandsToShow)
        }
    }

    private fun printTable(commands: List<QualifiedName>) {
        val table = table {
            borderType = BorderType.ROUNDED
            borderStyle = terminal.theme.muted

            header {
                row(Markdown("**Custom command name**"), Markdown("**ID of the plugin providing the command**"))
            }
            body {
                commands.forEach { command ->
                    row {
                        cell(terminal.theme.info(command.simpleName))
                        cell(command.pluginId)
                    }
                }
            }
        }
        terminal.print(table)
    }

    private fun printPlain(commands: List<QualifiedName>) {
        commands.forEach {
            terminal.println(it.qualifiedName)
        }
    }
}
