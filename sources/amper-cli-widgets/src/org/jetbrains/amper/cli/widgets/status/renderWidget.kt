/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.VerticalLayoutBuilder
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.ProgressBar
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

context(terminal: Terminal)
internal fun BuildState.render(): Widget = verticalLayout {
    val theme = terminal.theme
    // Required to explicitly fill empty space with whitespaces and overwrite old lines
    align = TextAlign.LEFT
    // Required to correctly truncate very long status lines (or on very narrow terminal windows)
    width = ColumnWidth.Expand()
    overflowWrap = OverflowWrap.ELLIPSES

    cell(horizontalLayout {
        cell("[")
        cell(
            ProgressBar(
                fractionComplete = completeTasksCount.toFloat() / totalTasksCount,
                width = min(40, terminal.size.width),
                completeStyle = theme.success,
            )
        )
        cell(buildString {
            append("] ")
            append(theme.success(completeTasksCount.toString()))
            append(theme.muted(" / $totalTasksCount tasks"))
        })
    })
    testStatistics?.let { cell(it.render()) }
    val maxTasksOnScreen = terminal.size.height / 3

    appendEntries(
        entries = taskStates.values.filter { it.shown },
        remainingLineBudget = LineBudget(maxTasksOnScreen),
    )
}

context(terminal: Terminal)
internal fun TestStatistics.render(): String = buildString {
    val theme = terminal.theme
    val visibleCounters = buildList {
        add(theme.success("$succeeded passed"))
        if (skipped > 0) add(theme.warning("$skipped skipped"))
        if (failed > 0) add(theme.danger("$failed failed"))
    }
    append("Tests: ")
    visibleCounters.joinTo(this, separator = theme.muted(" • "))
}

context(terminal: Terminal)
private fun VerticalLayoutBuilder.appendEntries(
    entries: Collection<StatusEntryState>,
    remainingLineBudget: LineBudget,
    indent: List<String> = [],
) {
    val theme = terminal.theme
    val isTopLevel = indent.isEmpty()
    entries.forEachIndexed { i, (renderedMoniker, childEntries, elapsed, ticks) ->
        val remaining = entries.size - i
        if (remainingLineBudget.value <= 0
            && remaining > 1  // no sense in replacing one real line with the '(+1 more)' line, might as well print it
        ) {
            val cutoffText = "(+$remaining more)"
            val cutoffLine = if (isTopLevel) cutoffText else buildString {
                indent.forEach { append(theme.muted(it)) }
                append(theme.muted("╰─ "))
                append(cutoffText)
            }
            cell(cutoffLine)
            return
        }

        remainingLineBudget.value--

        val isLast = i == entries.size - 1
        cell(buildString {
            val isLeafEntry = childEntries.isEmpty()
            indent.forEach {
                append(theme.muted(it))
            }
            if (isTopLevel) {
                append(theme.muted("→ "))
            } else {
                if (isLast) {
                    append(theme.muted("╰─ "))
                } else {
                    append(theme.muted("├─ "))
                }
            }
            if (isLeafEntry && !isTopLevel) {
                val spinnerChar = SpinnerFrames[ticks % SpinnerFrames.size]
                append(theme.success(spinnerChar)).append(' ')
            }
            append(renderedMoniker)
            if (elapsed >= 1.seconds) {
                append(theme.muted(" $elapsed"))
            }
        })

        val newIndent = if (isTopLevel) "  " else if (isLast) "   " else "│  "
        appendEntries(
            remainingLineBudget = remainingLineBudget,
            indent = indent + newIndent,
            entries = childEntries.values,
        )
    }
}

private data class LineBudget(var value: Int)

private val SpinnerFrames = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]
