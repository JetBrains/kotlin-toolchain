/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.tags

private const val NotOperator = "!"
private const val AndOperator = "&"
private const val OrOperator = "|"
private const val OpeningParenthesis = "("
private const val ClosingParenthesis = ")"
private const val AnyTagExpression = "any()"
private const val NoTagsExpression = "none()"

/**
 * All tokens that have a special meaning, and thus cannot be interpreted as tag names.
 */
private val ReservedTokens = setOf(NotOperator, AndOperator, OrOperator, OpeningParenthesis, ClosingParenthesis)

/**
 * A regex matching a single token of a tag expression: the reserved `any()`/`none()` expressions, a single-character
 * operator or parenthesis, or a tag name (any sequence of characters that is none of the above).
 *
 * Whitespace is not matched by any of the alternatives, and is therefore simply skipped between tokens.
 *
 * This is equivalent to the regex used by the JUnit Platform's own tokenizer.
 */
private val TokenRegex = Regex("""(?:any|none)\(\)|[()!|&]|[^\s()!|&]+""", RegexOption.IGNORE_CASE)

private data class Token(val text: String, val startIndex: Int)

/**
 * A recursive descent parser for JUnit tag expressions, as
 * [specified by JUnit](https://docs.junit.org/6.1.3/running-tests/tags.html#expressions).
 *
 * Expressions follow the following grammar (in increasing order of operator precedence):
 *
 * ```
 * orExpression  := andExpression ('|' andExpression)*
 * andExpression := notExpression ('&' notExpression)*
 * notExpression := '!' notExpression | primaryExpression
 * primaryExpression := '(' orExpression ')' | 'any()' | 'none()' | <tag name>
 * ```
 */
internal class TagExpressionParser(private val expression: String) {

    private val tokens = TokenRegex.findAll(expression).map { Token(it.value, it.range.first) }.toList()
    private var nextTokenIndex = 0

    fun parse(): JUnitTagExpression {
        if (tokens.isEmpty()) {
            fail("the expression must not be blank")
        }
        val parsed = parseOrExpression()
        val unexpectedToken = peekToken()
        if (unexpectedToken != null) {
            // this happens when 2 expressions follow each other without an operator between them, as in 'a b'
            fail("unexpected ${unexpectedToken.description()} (an operator was expected here)")
        }
        return parsed
    }

    private fun parseOrExpression(): JUnitTagExpression {
        var result = parseAndExpression()
        while (consumeTokenIfEquals(OrOperator)) {
            result = JUnitTagExpression.Or(left = result, right = parseAndExpression())
        }
        return result
    }

    private fun parseAndExpression(): JUnitTagExpression {
        var result = parseNotExpression()
        while (consumeTokenIfEquals(AndOperator)) {
            result = JUnitTagExpression.And(left = result, right = parseNotExpression())
        }
        return result
    }

    private fun parseNotExpression(): JUnitTagExpression = if (consumeTokenIfEquals(NotOperator)) {
        JUnitTagExpression.Not(operand = parseNotExpression())
    } else {
        parsePrimaryExpression()
    }

    private fun parsePrimaryExpression(): JUnitTagExpression {
        val token = nextToken() ?: fail("unexpected end of expression (a tag name was expected)")
        return when {
            token.text == OpeningParenthesis -> parseParenthesizedExpression(openingParenthesis = token)
            token.text.equals(AnyTagExpression, ignoreCase = true) -> JUnitTagExpression.AnyTag
            token.text.equals(NoTagsExpression, ignoreCase = true) -> JUnitTagExpression.NoTags
            token.text in ReservedTokens -> fail("unexpected ${token.description()} (a tag name was expected)")
            else -> JUnitTagExpression.Tag(name = token.readTagName())
        }
    }

    private fun parseParenthesizedExpression(openingParenthesis: Token): JUnitTagExpression {
        val innerExpression = parseOrExpression()
        if (!consumeTokenIfEquals(ClosingParenthesis)) {
            fail("missing closing parenthesis for the ${openingParenthesis.description()}")
        }
        return innerExpression
    }

    /**
     * Returns the text this token, ensuring it is a valid tag name.
     *
     * JUnit requires tag names to be non-blank, and to contain no whitespace, no ISO control characters, and none of
     * the reserved characters `,`, `(`, `)`, `&`, `|`, `!`.
     * This token cannot contain whitespace nor reserved characters other than `,` by construction, so only the
     * remaining rules are checked here.
     */
    private fun Token.readTagName(): String {
        if (text.any { it == ',' || Character.isISOControl(it) }) {
            fail("invalid tag name '$text' at index $startIndex (tag names must not contain the ',' character nor " +
                    "ISO control characters)")
        }
        return text
    }

    private fun Token.description(): String = "'$text' at index $startIndex"

    private fun peekToken(): Token? = tokens.getOrNull(nextTokenIndex)

    private fun nextToken(): Token? = peekToken()?.also { nextTokenIndex++ }

    private fun consumeTokenIfEquals(tokenText: String): Boolean {
        val matches = peekToken()?.text == tokenText
        if (matches) {
            nextTokenIndex++
        }
        return matches
    }

    private fun fail(reason: String): Nothing =
        throw JUnitTagExpressionSyntaxException("invalid tag expression '$expression': $reason")
}
