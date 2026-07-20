/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.io.IOException
import org.jetbrains.amper.compilation.CompilerBuildProblem
import org.jetbrains.amper.frontend.catalogs.ComposeMaterial3UnknownVersionMappingProblem
import org.jetbrains.amper.frontend.messages.computeRange
import org.jetbrains.amper.frontend.messages.renderMessage
import org.jetbrains.amper.frontend.tree.ConflictingProperties
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.FileBuildProblemSource
import org.jetbrains.amper.problems.reporting.FileWithLineColumnProblemSource
import org.jetbrains.amper.problems.reporting.FileWithRangesBuildProblemSource
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.LineAndColumnRange
import org.jetbrains.amper.problems.reporting.MultipleLocationsBuildProblemSource
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.relativeToOrSelf
import kotlin.io.path.useLines

/**
 * A [ProblemReporter] that displays problems on the terminal with highlighted source snippets when available.
 */
class RichTerminalProblemReporter(
    private val terminal: Terminal,
    private val projectRoot: Path?,
) : ProblemReporter {
    override fun reportMessage(message: BuildProblem) {
        when (message) {
            // TODO: These need to be niceified as well?
            is ConflictingProperties,
            is ComposeMaterial3UnknownVersionMappingProblem,
                -> terminal.println(renderMessage(message), stderr = message.level == Level.Error)

            else -> render(message)
        }
    }

    @OptIn(NonIdealDiagnostic::class)
    private fun render(problem: BuildProblem) {
        val moduleName = (problem as? CompilerBuildProblem)?.moduleName
        val message = when (val source = problem.source) {
            is FileBuildProblemSource -> renderImpl(source, problem.level, problem.message, moduleName)
            is MultipleLocationsBuildProblemSource -> {
                val renderedLocations = source.sources.joinToString("\n") {
                    renderImpl(it, problem.level, message = null, moduleName = null, minGutterWidth = 0)
                }
                val borderPrefix = " ".repeat(MIN_GUTTER_WIDTH + 1)
                buildString {
                    appendLine("$borderPrefix╭─ ${severityStyledText(problem.level)}${TextStyles.bold(problem.message)}")
                    renderedLocations.lines().joinTo(
                        buffer = this,
                        prefix = "$borderPrefix├── ${source.groupingMessage}\n",
                        transform = { "$borderPrefix│ $it\n" },
                        separator = "",
                        postfix = "$borderPrefix╰─",
                    )
                }
            }
            GlobalBuildProblemSource -> "${severityStyledText(problem.level)}${TextStyles.bold(problem.message)}"
        }
        terminal.println(message, stderr = problem.level == Level.Error)
    }

    private fun FileBuildProblemSource.getLineColumnRange(): LineAndColumnRange? = when (this) {
        is FileWithLineColumnProblemSource -> lineColumnRange
        is FileWithRangesBuildProblemSource -> computeRange()
        else -> null
    }

    private fun renderImpl(
        source: FileBuildProblemSource,
        level: Level,
        message: String?,
        moduleName: String?,
        minGutterWidth: Int = MIN_GUTTER_WIDTH,
    ): String {
        val span = source.getLineColumnRange()
        val location = buildString {
            append(if (projectRoot != null) source.file.relativeToOrSelf(projectRoot) else source.file)
            span?.let { append(":${it.start.line}:${it.start.column}") }
            moduleName?.let { append(" ($it)") }
        }
        val locationWithHyperlink = TextStyles.hyperlink("file://${source.file}")(location)
        val snippet = span?.let { resolveSnippet(source.file, it) }
        val severityStyle = severityStyle(level)
        val muted = terminal.theme.muted

        return buildString {
            if (span != null && snippet != null) {
                val maxLineNo = span.start.line + snippet.size - 1
                val gutterWidth = maxLineNo.toString().length.coerceAtLeast(minGutterWidth)
                val borderPrefix = " ".repeat(gutterWidth + 1)
                val isMultiLine = snippet.size > 1
                append(muted("$borderPrefix╭─ "))
                if (message != null) {
                    append(severityStyledText(level))
                    message.lines().joinTo(
                        buffer = this,
                        separator = "\n$borderPrefix│ ",
                    )
                } else {
                    append(locationWithHyperlink)
                }
                appendLine()

                if (message != null) {
                    append(muted("$borderPrefix│ → "))
                    append(locationWithHyperlink)
                    appendLine()
                }

                appendLine(muted("$borderPrefix│"))

                if (isMultiLine) {
                    append(muted("$borderPrefix│ "))
                    append(severityStyle(buildTopPointer(span, snippet.first())))
                    appendLine()
                }

                snippet.forEachIndexed { i, line ->
                    val lineNo = (span.start.line + i).toString().padStart(gutterWidth)
                    append(muted("$lineNo │ "))
                    append(highlightRange(line, span, i, snippet.size, severityStyle))
                    appendLine()
                }

                append(muted("$borderPrefix│ "))
                append(severityStyle(buildBottomPointer(span, isMultiLine)))
                appendLine()

                append(muted("$borderPrefix╰─"))
            } else if (message != null) {
                append(severityStyledText(level))
                append("$locationWithHyperlink: ")
                append(TextStyles.bold(message))
            } else {
                append(locationWithHyperlink)
            }
        }
    }

    private fun severityStyle(level: Level): TextStyle = when (level) {
        Level.Error -> terminal.theme.danger
        Level.Warning, Level.WeakWarning -> terminal.theme.warning
    }

    private fun severityStyledText(level: Level) = when (level) {
        Level.WeakWarning -> "WEAK WARNING"
        Level.Warning -> "WARNING"
        Level.Error -> "ERROR"
    }.let { severityStyle(level)(TextStyles.bold("$it: ")) }

    private fun resolveSnippet(file: Path, span: LineAndColumnRange): List<String>? {
        val lines = try {
            file.useLines { linesSequence ->
                linesSequence
                    // Lines in LineAndColumnRange are 1-based.
                    .drop(span.start.line - 1)
                    .take(span.end.line.coerceAtLeast(span.start.line) - span.start.line + 1)
                    .toList()
            }
        } catch (e: IOException) {
            internalLogger.error("Failed to read file snippet for location: $file:${span.start.line}-${span.end.line}", e)
            return null
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun buildTopPointer(span: LineAndColumnRange, firstLine: String): String {
        val padding = span.start.column - 1
        val length = firstLine.length - padding
        return " ".repeat(padding) + "⌄".repeat(length)
    }

    private fun buildBottomPointer(span: LineAndColumnRange, isMultiLine: Boolean): String = if (isMultiLine) {
        val length = (span.end.column - 1).coerceAtLeast(1)
        "⌃".repeat(length)
    } else {
        val padding = span.start.column - 1
        val length = if (span.end.column > span.start.column) {
            span.end.column - span.start.column
        } else {
            1
        }
        " ".repeat(padding) + "⌃".repeat(length)
    }

    private fun highlightRange(
        line: String,
        span: LineAndColumnRange,
        lineIndex: Int,
        totalLines: Int,
        style: TextStyle,
    ): String {
        val start: Int
        val end: Int
        when {
            totalLines == 1 -> {
                start = (span.start.column - 1).coerceIn(0, line.length)
                end = if (span.end.column > span.start.column) {
                    (span.end.column - 1).coerceIn(start, line.length)
                } else {
                    (start + 1).coerceAtMost(line.length)
                }
            }
            lineIndex == 0 -> {
                start = (span.start.column - 1).coerceIn(0, line.length)
                end = line.length
            }
            lineIndex == totalLines - 1 -> {
                start = 0
                end = if (span.end.column > 0) {
                    (span.end.column - 1).coerceIn(0, line.length)
                } else {
                    line.length
                }
            }
            else -> {
                start = 0
                end = line.length
            }
        }
        return line.substring(0, start) + style(line.substring(start, end)) + line.substring(end)
    }

    private val internalLogger = LoggerFactory.getLogger(javaClass)
}

// At least 3, so most diagnostics are aligned
private const val MIN_GUTTER_WIDTH = 3
