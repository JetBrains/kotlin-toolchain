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
import org.jetbrains.amper.cli.CheckEntry
import org.jetbrains.amper.cli.QualifiedName
import org.jetbrains.amper.cli.commands.AmperModelAwareCommand
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.filterByPluginId
import org.jetbrains.amper.cli.options.ModuleFilter
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.options.selectModules
import org.jetbrains.amper.frontend.Model

private enum class ChecksListFormat(val cliName: String) {
    Plain("plain"),
    Table("table"),
}

internal class ShowChecksCommand : AmperModelAwareCommand(name = "checks") {

    private val modules by moduleOption(
        help = """
            The module to show the checks of (run the `show modules` command to get the modules list).
            This option can be repeated to show checks for several modules.
            If unspecified, checks for all modules are shown.
        """.trimIndent(),
    ).transformAll { if (it.isEmpty()) null else ModuleFilter.Names(it.toSet()) }

    private val pluginIdFilter by option(
        "--plugin",
        help = "Filter checks by plugin ID. If specified, only checks from the given plugin are shown."
    )

    private val format by option(
        "--format",
        help = """
            The format of the output. Available formats:
             - `plain`: plain, unformatted list of *qualified* check names
             - `table`: formatted table with multiple columns
        """.trimIndent(),
    ).enum<ChecksListFormat> { it.cliName }
        .default(ChecksListFormat.Table, defaultForHelp = "table")

    override fun help(context: Context): String = "Print all available checks in the project"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        val selectedModules = (modules ?: ModuleFilter.All).selectModules(model.modules)

        val allChecks = selectedModules.flatMap { it.checksFromPlugins }
            .map { CheckEntry.Custom(it).name }
            .distinct()
            .plus(CheckEntry.Tests.name)

        val checksToShow = allChecks.run {
            pluginIdFilter?.let { filterByPluginId(model, it) } ?: this
        }.sorted()

        if (checksToShow.isEmpty()) {
            terminal.println("No checks found for the given filter.")
            return
        }

        when (format) {
            ChecksListFormat.Plain -> printPlain(checksToShow)
            ChecksListFormat.Table -> printTable(checksToShow)
        }
    }

    private fun printTable(checks: List<QualifiedName>) {
        val table = table {
            borderType = BorderType.ROUNDED
            borderStyle = terminal.theme.muted

            header {
                row(Markdown("**Check name**"), Markdown("**ID of the plugin providing the check**"))
            }
            body {
                checks.forEach { check ->
                    row {
                        cell(terminal.theme.info(check.simpleName))
                        cell(check.pluginId ?: terminal.theme.muted("(builtin)"))
                    }
                }
            }
        }
        terminal.print(table)
    }

    private fun printPlain(checks: List<QualifiedName>) {
        checks.forEach {
            terminal.println(it.qualifiedName)
        }
    }
}
