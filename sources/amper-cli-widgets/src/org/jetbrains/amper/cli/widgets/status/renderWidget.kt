/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.VerticalLayoutBuilder
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.ProgressBar
import org.jetbrains.amper.events.payload.ProgressState
import kotlin.math.min
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

context(terminal: Terminal)
internal fun BuildState.render(
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
): Widget = verticalLayout {
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
    if (testStatistics.started) {
        cell(testStatistics.render())
    }

    val now = timeSource.markNow()
    appendEntries(
        now = now,
        entries = taskStates.filter {
            it.showImmediately || (now - it.startTime >= WidgetTimings.StatusEntryAppearDelay)
        },
        remainingLineBudget = LineBudget(terminal),
        showSpinner = false,  // Do not show a spinner for top-level (tasks)
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
internal fun Collection<StatusEntryState>.renderEntries(
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) = verticalLayout {
    appendEntries(
        entries = this@renderEntries,
        remainingLineBudget = LineBudget(terminal),
        now = timeSource.markNow(),
        showSpinner = true,
    )
}

context(terminal: Terminal)
private fun VerticalLayoutBuilder.appendEntries(
    entries: Collection<StatusEntryState>,
    remainingLineBudget: LineBudget,
    now: ComparableTimeMark,
    showSpinner: Boolean,
    indent: List<String> = [],
) {
    val theme = terminal.theme
    val isTopLevel = indent.isEmpty()
    entries.forEachIndexed { i, (renderedMoniker, nestedEntries, startTime, progressState) ->
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
            val elapsed = now - startTime
            val ticks = (elapsed / WidgetTimings.SpinnerTickInterval).toInt()
            val isLeafEntry = nestedEntries.isEmpty()
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
            if (isLeafEntry && showSpinner) {
                val spinnerChar = SpinnerFrames[ticks % SpinnerFrames.size]
                append(theme.success(spinnerChar)).append(' ')
            }

            val percentage = when (progressState) {
                is ProgressState.Downloading -> progressState.bytesTotal?.let { progressState.bytesDone * 100 / it }
                is ProgressState.Generic -> (progressState.ratio * 100f).toInt()
                ProgressState.Indeterminate -> null
            }

            if (percentage != null) {
                val style = theme.success + TextStyles.bold.style
                append(style("$percentage % "))
            }

            append(renderedMoniker)

            when (progressState) {
                is ProgressState.Downloading -> {
                    val downloadStatsString = buildString {
                        append(" (").append(formatBytes(progressState.bytesDone))
                        progressState.bytesTotal?.let {
                            append('/').append(formatBytes(it))
                        }
                        progressState.speed?.let {
                            append(" @ ").append(formatBytes(it)).append("/s")
                        }
                        append(')')
                    }
                    append(theme.muted(downloadStatsString))
                }
                is ProgressState.Generic,
                ProgressState.Indeterminate,
                    -> Unit
            }

            if (elapsed >= 1.seconds) {
                append(theme.muted(" ${elapsed.inWholeSeconds.seconds}"))
            }
        })

        val newIndent = if (isTopLevel) "  " else if (isLast) "   " else "│  "
        appendEntries(
            remainingLineBudget = remainingLineBudget,
            indent = indent + newIndent,
            entries = nestedEntries,
            now = now,
            showSpinner = true,  // Show for nested operations
        )
    }
}

private data class LineBudget(var value: Int) {
    constructor(terminal: Terminal) : this(terminal.size.height / 3)
}

private val SpinnerFrames = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_000) return "$bytes B"
    var value = bytes / 1_000.0
    for (unit in DataUnitAbbreviations) {
        if (value < 1_000 || unit == DataUnitAbbreviations.last()) {
            return if (value < 10) "%.1f %s".format(value, unit) else "%.0f %s".format(value, unit)
        }
        value /= 1_000.0
    }
    return "$bytes B"
}

private val DataUnitAbbreviations = ["KB", "MB", "GB", "TB"]
