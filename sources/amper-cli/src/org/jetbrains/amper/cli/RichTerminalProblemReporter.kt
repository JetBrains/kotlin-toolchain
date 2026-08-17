/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.Line
import com.github.ajalt.mordant.rendering.Lines
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.rendering.WidthRange
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import kotlinx.io.IOException
import org.jetbrains.amper.cli.widgets.withStyle
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
            is FileBuildProblemSource -> renderImpl(source, problem.level, problemTextWidget(problem), moduleName)
            is MultipleLocationsBuildProblemSource -> {
                val muted = terminal.theme.muted
                val borderPrefix = " ".repeat(MIN_GUTTER_WIDTH + 1)
                verticalLayout {
                    cell(
                        PrefixedWidget(
                            prefix = muted("$borderPrefix╭─ ") + severityStyledText(problem.level),
                            continuationPrefix = muted("$borderPrefix│ "),
                            content = problemTextWidget(problem),
                        )
                    )
                    cell(muted("$borderPrefix├── ") + source.groupingMessage)
                    cell(
                        PrefixedWidget(
                            prefix = muted("$borderPrefix│ "),
                            content = verticalLayout {
                                source.sources.forEach { source ->
                                    cell(
                                        renderImpl(
                                            source, problem.level,
                                            message = null, moduleName = null, minGutterWidth = 0,
                                        )
                                    )
                                }
                            },
                        )
                    )
                    cell(muted("$borderPrefix╰─"))
                }
            }
            GlobalBuildProblemSource -> PrefixedWidget(
                prefix = severityStyledText(problem.level),
                continuationPrefix = " ".repeat(severityPrefix(problem.level).length),
                content = problemTextWidget(problem),
            )
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
        message: Widget?,
        moduleName: String?,
        minGutterWidth: Int = MIN_GUTTER_WIDTH,
    ): Widget {
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

        if (span == null || snippet == null) {
            return if (message != null) {
                verticalLayout {
                    cell(horizontalLayout {
                        cells(severityStyledText(level), message)
                        spacing = 0
                    })
                    cell(muted(" ╰→ ") + locationWithHyperlink)
                }
            } else {
                Text(locationWithHyperlink)
            }
        }

        val maxLineNo = span.start.line + snippet.size - 1
        val gutterWidth = maxLineNo.toString().length.coerceAtLeast(minGutterWidth)
        val borderPrefix = " ".repeat(gutterWidth + 1)
        val isMultiLine = snippet.size > 1
        return verticalLayout {
            if (message != null) {
                cell(
                    PrefixedWidget(
                        prefix = muted("$borderPrefix╭─ ") + severityStyledText(level),
                        continuationPrefix = muted("$borderPrefix│ "),
                        trailingContinuationIfMultiline = muted("$borderPrefix│"),
                        content = message,
                    )
                )
                cell(muted("$borderPrefix│ → ") + locationWithHyperlink)
            } else {
                cell(muted("$borderPrefix╭─ ") + locationWithHyperlink)
            }

            cell(muted("$borderPrefix│"))

            if (isMultiLine) {
                cell(muted("$borderPrefix│ ") + severityStyle(buildTopPointer(span, snippet.first())))
            }

            snippet.forEachIndexed { i, line ->
                val lineNo = (span.start.line + i).toString().padStart(gutterWidth)
                cell(muted("$lineNo │ ") + highlightRange(line, span, i, snippet.size, severityStyle))
            }

            cell(muted("$borderPrefix│ ") + severityStyle(buildBottomPointer(span, isMultiLine)))
            cell(muted("$borderPrefix╰─"))
        }
    }

    private fun problemTextWidget(problem: BuildProblem): Widget {
        if (problem is CompilerBuildProblem ||  // We don't own those messages - they are not Markdown
            // It's better to leave potential Markdown markup than to lose the info altogether.
            !terminal.terminalInfo.interactive ||
            terminal.terminalInfo.ansiLevel < AnsiLevel.ANSI256
        ) {
            // Return message as is
            return Text(problem.message, whitespace = Whitespace.PRE_WRAP)
                .withStyle(TextStyles.bold.style)
        }

        // Render as Markdown
        return Markdown(
            // We'd like to treat newlines as paragraphs
            problem.message.replace("\n", "  \n"),
            showHtml = true,  // Sometimes we use `<` or `>` in the messages that are not escaped or in the code.
        ).withStyle(TextStyles.bold.style)
    }

    private fun severityStyle(level: Level): TextStyle = when (level) {
        Level.Error -> terminal.theme.danger
        Level.Warning, Level.WeakWarning -> terminal.theme.warning
    }

    private fun severityPrefix(level: Level) = when (level) {
        Level.WeakWarning -> "WEAK WARNING"
        Level.Warning -> "WARNING"
        Level.Error -> "ERROR"
    } + ": "

    private fun severityStyledText(level: Level) =
        severityStyle(level)(TextStyles.bold(severityPrefix(level)))

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

/**
 * Prepends [prefix] to the first line of the rendered [content],
 * prepends [continuationPrefix] to the following lines, if any.
 *
 * If [trailingContinuationIfMultiline] is not `null` and the [content] is multiline,
 *  then add an extra line consisting of this string at the end.
 *
 * NOTE: all the prefix values and trailing continutation must be single line.
 */
private class PrefixedWidget(
    prefix: String,
    continuationPrefix: String = prefix,
    trailingContinuationIfMultiline: String? = null,
    private val content: Widget,
) : Widget {
    private val firstPrefix = Text(prefix)
    private val continuationPrefix = Text(continuationPrefix)
    private val trailingContinuationIfMultiline = trailingContinuationIfMultiline?.let(::Text)

    override fun measure(t: Terminal, width: Int): WidthRange {
        val prefixWidth = firstPrefix.measure(t, width).max
        return content.measure(t, (width - prefixWidth).coerceAtLeast(1)) + prefixWidth
    }

    override fun render(t: Terminal, width: Int): Lines {
        val firstPrefixLine = firstPrefix.render(t, width).lines.first()
        val prefixWidth = firstPrefix.measure(t, width).max
        val continuationPrefixLine = continuationPrefix.render(t, width).lines.first()
        val contentLines = content.render(t, (width - prefixWidth).coerceAtLeast(1)).lines
        if (contentLines.isEmpty()) return Lines(listOf(firstPrefixLine))

        return Lines(buildList {
            contentLines.forEachIndexed { index, line ->
                val prefix = if (index == 0) firstPrefixLine else continuationPrefixLine
                add(Line(prefix.spans + line.spans, line.endStyle))
            }
            if (trailingContinuationIfMultiline != null && contentLines.size > 1) {
                add(trailingContinuationIfMultiline.render(t, width).lines.first())
            }
        })
    }
}

// At least 3, so most diagnostics are aligned
private const val MIN_GUTTER_WIDTH = 3
