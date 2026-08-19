/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.cli.widgets.withStyle
import org.jetbrains.amper.events.payload.TaskMonikerSpec

context(terminal: Terminal)
fun TaskMonikerSpec.render(): String {
    val theme = terminal.theme
    return when (this) {
        is TaskMonikerSpec.ProjectScoped -> {
            renderMarkdownSingleLine(operationMoniker, style = TextStyles.bold.style)
        }
        is TaskMonikerSpec.CompilationScoped -> buildString {
            appendModule(moduleName, theme = theme)
            append(' ')
            append(renderMarkdownSingleLine(operationMoniker, style = TextStyles.bold.style))
            append(' ')
            append(theme.muted("[${platform}]"))
            if (buildType != null) {
                append(' ')
                append(theme.muted("[${buildType}]"))
            }
            if (isTest) {
                append(' ')
                append(theme.muted("for unit tests"))
            }
        }
        is TaskMonikerSpec.FragmentScoped -> buildString {
            appendModule(moduleName, theme = theme)
            append(' ')
            append(renderMarkdownSingleLine(operationMoniker, style = TextStyles.bold.style))
            append(' ')
            append(theme.muted("[${fragmentName}]"))
        }
        is TaskMonikerSpec.ModuleScoped -> buildString {
            appendModule(moduleName, theme = theme)
            append(' ')
            append(renderMarkdownSingleLine(operationMoniker, style = TextStyles.bold.style))
        }
        is TaskMonikerSpec.FromPlugin -> buildString {
            appendModule(moduleName, theme = theme)
            append(" running ")
            append(theme.style("markdown.code.span")(name))
            append(theme.muted(" from plugin '${pluginId}'"))
        }
    }
}

context(theme: Theme)
private fun StringBuilder.appendModule(module: String) {
    append(theme.muted("module ")).append(theme.info(module)).append(theme.muted(":"))
}

/**
 * Renders the [markdown] into the ANSI-sequence string without any implicit line-breaks.
 */
context(terminal: Terminal)
private fun renderMarkdownSingleLine(
    markdown: String,
    style: TextStyle? = null,
): String {
    var widget: Widget = Markdown(markdown)
    if (style != null) {
        widget = widget.withStyle(style)
    }
    return terminal.render(
        message = widget,
        width = Int.MAX_VALUE,
    )
}
