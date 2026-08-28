/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.tags

/**
 * A parsed [JUnit tag expression](https://docs.junit.org/current/user-guide/#running-tests-tag-expressions).
 *
 * A tag expression is a boolean expression on the tags of a test, which is used to decide whether the test is selected.
 * Use [parse] to get an instance from the textual form of the expression, and [matches] to evaluate it against the
 * tags of a test.
 */
sealed interface JUnitTagExpression {

    /**
     * Matches tests that are tagged with the tag called [name].
     */
    data class Tag(val name: String) : JUnitTagExpression

    /**
     * Matches tests that have at least one tag (the `any()` expression).
     */
    data object AnyTag : JUnitTagExpression

    /**
     * Matches tests that have no tags at all (the `none()` expression).
     */
    data object NoTags : JUnitTagExpression

    /**
     * Matches tests that don't match the [operand] expression (the `!` operator).
     */
    data class Not(val operand: JUnitTagExpression) : JUnitTagExpression

    /**
     * Matches tests that match both the [left] and [right] expressions (the `&` operator).
     */
    data class And(val left: JUnitTagExpression, val right: JUnitTagExpression) : JUnitTagExpression

    /**
     * Matches tests that match the [left] expression, the [right] expression, or both (the `|` operator).
     */
    data class Or(val left: JUnitTagExpression, val right: JUnitTagExpression) : JUnitTagExpression

    companion object {
        /**
         * Parses the given textual JUnit tag [expression].
         *
         * @throws JUnitTagExpressionSyntaxException if [expression] is not a valid JUnit tag expression
         */
        fun parse(expression: String): JUnitTagExpression = TagExpressionParser(expression).parse()
    }
}

/**
 * Returns whether a test with the given [tags] matches this expression.
 *
 * The given [tags] are expected to be normalized the way JUnit normalizes them, which means they are trimmed.
 * An empty set of [tags] represents a test without any tag.
 */
fun JUnitTagExpression.matches(tags: Set<String>): Boolean = when (this) {
    is JUnitTagExpression.Tag -> name in tags
    JUnitTagExpression.AnyTag -> tags.isNotEmpty()
    JUnitTagExpression.NoTags -> tags.isEmpty()
    is JUnitTagExpression.Not -> !operand.matches(tags)
    is JUnitTagExpression.And -> left.matches(tags) && right.matches(tags)
    is JUnitTagExpression.Or -> left.matches(tags) || right.matches(tags)
}

/**
 * Thrown when a textual JUnit tag expression cannot be parsed.
 */
class JUnitTagExpressionSyntaxException(override val message: String) : IllegalArgumentException(message)
