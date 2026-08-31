/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.kotlin.compiler.messages

import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerMessage.Location
import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerMessage.Severity

/**
 * Parses the output of a Kotlin CLI compiler (`kotlinc`, `konanc`, `cinterop`) line by line.
 *
 * The Kotlin CLI compilers print their messages in the following plain-text format:
 * ```
 * warning: flag is not supported by this version of the compiler: -Xfoo-bar
 * src/Foo.kt:5:13: error: unresolved reference 'undefinedThing'.
 *     println(undefinedThing)
 *             ^^^^^^^^^^^^^^
 * ```
 * The location prefix is only present for messages about a specific place in the source code, and is followed by a
 * snippet of the source code with carets highlighting the range of the message. The text of a message can span multiple
 * lines (and even contain blank lines), in which case the source code snippet comes after all the message lines.
 *
 * Since nothing marks the end of a message, all lines following a message are considered part of it until the next
 * message starts. This means that output that is not printed by the compiler itself (for instance, the output of tools
 * spawned by the compiler) is only recognized as [UnrecognizedOutputLine] if it doesn't directly follow a message.
 *
 * This class is stateful and not thread-safe. Because a multi-line message can only be known to be complete when the
 * next message starts, one instance must be used per stream (the compiler writes stdout and stderr independently), and
 * [flush] must be called when the stream is exhausted to get the last message.
 */
class KotlinCompilerOutputParser(
    private val onCompleteMessage: (KotlinCompilerOutputItem) -> Unit,
) {
    private var currentMessage: PartialMessage? = null

    /**
     * Consumes the given output [line], and possibly sends a [KotlinCompilerOutputItem] via [onCompleteMessage], if
     * this new line concludes the previous message.
     *
     * Lines that follow a message are considered part of that message, so a message is only sent when the line that
     * follows it starts a new message (or when [flush] is called).
     */
    fun consumeLine(line: String) {
        val newMessage = line.parseMessageFirstLine()
        if (newMessage != null) {
            completeCurrentMessage()
            currentMessage = newMessage
            return
        }
        // if currentMessage is null, it means we have no started message, and this new line is not a message start
        currentMessage?.followingLines?.add(line) ?: onCompleteMessage(UnrecognizedOutputLine(line))
    }

    /**
     * Completes and sends the message that is currently being parsed, if any.
     *
     * This must be called when [consumeLine] will no longer be called (e.g. when the stream ends), because messages
     * can span multiple lines and thus are usually only complete when the next message starts.
     */
    fun flush() {
        completeCurrentMessage()
    }

    private fun completeCurrentMessage() {
        val message = currentMessage ?: return
        currentMessage = null
        onCompleteMessage(message.complete())
    }
}

/**
 * Matches the first line of a compiler message, such as `src/Foo.kt:5:13: error: unresolved reference 'x'.`.
 * The location prefix (path, line, and column) is optional, and so is the column within that prefix.
 */
private val messageStartRegex = Regex(
    """(?:(?<path>.+?):(?<line>\d+):(?:(?<column>\d+):)? )?""" +
            """(?<severity>${Severity.entries.joinToString("|") { it.cliName }}): (?<text>.*)"""
)

private val severitiesByCliName = Severity.entries.associateBy { it.cliName }

private fun String.parseMessageFirstLine(): PartialMessage? {
    val groups = messageStartRegex.matchEntire(this)?.groups ?: return null
    val severity = severitiesByCliName[groups["severity"]?.value] ?: return null
    val path = groups["path"]?.value
    return PartialMessage(
        severity = severity,
        firstTextLine = groups["text"]?.value ?: "",
        location = path?.let {
            Location(
                path = it,
                line = groups["line"]?.value?.toInt() ?: error("the line number is always present with a path"),
                columnStart = groups["column"]?.value?.toInt(),
                // The end column is only known once we see the carets under the source code line
                columnEnd = null,
            )
        },
    )
}

/**
 * Matches the line of carets that the compiler prints under the source code of a located message.
 */
private val underlineRegex = Regex("""[ \t]*\^+""")

private class PartialMessage(
    val severity: Severity,
    val firstTextLine: String,
    val location: Location?,
) {
    val followingLines = mutableListOf<String>()

    fun complete(): KotlinCompilerMessage {
        val caretsLine = followingLines.lastOrNull()
            ?.takeIf { location != null && followingLines.size >= 2 && underlineRegex.matches(it) }
        // Remove snippets lines from the message's text (will be rendered separately)
        // The CLI compliler never prints more than one line of snippet. If the error location is multi-line, the first
        // line is printed with a single caret. So it seems this is the best we can do while waiting for the BTA.
        val textPart2 = if (caretsLine == null) followingLines else followingLines.dropLast(2)
        return KotlinCompilerMessage(
            severity = severity,
            text = ([firstTextLine] + textPart2).joinToString("\n"),
            location = location?.withColumnEndFrom(caretsLine),
        )
    }
}

/**
 * Returns a copy of this location with the end column deduced from the number of carets in the given [caretsLine].
 */
private fun Location.withColumnEndFrom(caretsLine: String?): Location {
    if (caretsLine == null || columnStart == null) return this
    // We don't know if the snippet itself is indented in the output, so we can't use the last caret's position.
    // This is why we start from columnStart and count the carets. Also, snippets are apparently never 
    // multiline in the compiler CLI, so it's safe to add the carets to columnStart.
    return copy(columnEnd = columnStart + caretsLine.count { it == '^' })
}
