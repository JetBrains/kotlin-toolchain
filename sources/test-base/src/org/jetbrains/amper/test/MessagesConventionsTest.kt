/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test

@Tag("gold-file")
abstract class MessagesConventionsTest(private val bundleName: String) {
    @Test
    fun messagesAreSortedAlphabetically() {
        val bundlePath = Path("resources/messages/$bundleName.properties").takeIf { it.exists() }
        val bundleText = bundlePath?.readText()?.trim() ?: error("$bundleName.properties file not found")

        val sorted = sortPropertiesFileContent(bundleText)

        assertEqualsIgnoreLineSeparator(bundleText, sorted, bundlePath)
    }
}

/**
 * Sorts properties file content alphabetically by property keys.
 * Preserves empty lines, comments (lines starting with # or !), and multi-line properties.
 * Empty lines and comments are attached to the property that follows them.
 */
internal fun sortPropertiesFileContent(content: String): String {
    // Collect blocks: each block is (preceding lines: empty/comments, property lines)
    val blocks = mutableListOf<Block>()
    var pendingPrecedingLines = mutableListOf<String>()

    content.lines().forEach { line ->
        when {
            line.isBlank() || line.startsWith('#') || line.startsWith('!') -> {
                pendingPrecedingLines.add(line)
            }
            blocks.lastOrNull()?.meaningfulFragment?.endsWith('\\') == true -> {
                // Continuation of previous property
                blocks.last().meaningfulFragment.append("\n$line")
            }
            else -> {
                // New property - attach pending preceding lines to it
                blocks += Block(pendingPrecedingLines, StringBuilder().append(line))
                pendingPrecedingLines = mutableListOf()
            }
        }
    }

    if (blocks.isEmpty()) {
        return pendingPrecedingLines.joinToString(separator = "\n")
    }

    val trailingSuffix = if (pendingPrecedingLines.isEmpty()) {
        ""
    } else {
        "\n" + pendingPrecedingLines.joinToString(separator = "\n")
    }

    return blocks
        .sortedBy { parsePropertyKey(it.meaningfulFragment.toString()) }
        .joinToString("\n") { (precedingCommentsAndEmptyLines, meaningfulFragment) ->
            (precedingCommentsAndEmptyLines + meaningfulFragment.toString()).joinToString("\n")
        } + trailingSuffix
}

private data class Block(val precedingCommentsAndEmptyLines: List<String>, val meaningfulFragment: StringBuilder)

private val singleLinePropertyRegex = Regex("(?<key>[^=]+)=(?<value>.*)", RegexOption.DOT_MATCHES_ALL)

private fun parsePropertyKey(line: String): String {
    val match = singleLinePropertyRegex.matchEntire(line)
        ?: error("Line doesn't match the expected key=value syntax: $line")
    return match.groups["key"]?.value ?: error("Key group is missing from match (impossible!)")
}
