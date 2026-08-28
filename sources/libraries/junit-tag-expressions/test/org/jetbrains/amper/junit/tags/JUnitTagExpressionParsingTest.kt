/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.tags

import org.jetbrains.amper.junit.tags.JUnitTagExpression.And
import org.jetbrains.amper.junit.tags.JUnitTagExpression.AnyTag
import org.jetbrains.amper.junit.tags.JUnitTagExpression.NoTags
import org.jetbrains.amper.junit.tags.JUnitTagExpression.Not
import org.jetbrains.amper.junit.tags.JUnitTagExpression.Or
import org.jetbrains.amper.junit.tags.JUnitTagExpression.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JUnitTagExpressionParsingTest {

    @Test
    fun `single tag`() {
        assertParsedAs(Tag("slow"), "slow")
    }

    @Test
    fun `tag names may contain non-alphanumeric characters`() {
        assertParsedAs(Tag("my-tag_1.2#3*4"), "my-tag_1.2#3*4")
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertParsedAs(Tag("slow"), "  slow\t")
    }

    @Test
    fun `not operator`() {
        assertParsedAs(Not(Tag("slow")), "!slow")
        assertParsedAs(Not(Tag("slow")), " ! slow ")
    }

    @Test
    fun `repeated not operators`() {
        assertParsedAs(Not(Not(Tag("slow"))), "!!slow")
    }

    @Test
    fun `and operator`() {
        assertParsedAs(And(Tag("slow"), Tag("flaky")), "slow & flaky")
        assertParsedAs(And(Tag("slow"), Tag("flaky")), "slow&flaky")
    }

    @Test
    fun `or operator`() {
        assertParsedAs(Or(Tag("slow"), Tag("flaky")), "slow | flaky")
        assertParsedAs(Or(Tag("slow"), Tag("flaky")), "slow|flaky")
    }

    @Test
    fun `and operator is left-associative`() {
        assertParsedAs(And(And(Tag("a"), Tag("b")), Tag("c")), "a & b & c")
    }

    @Test
    fun `or operator is left-associative`() {
        assertParsedAs(Or(Or(Tag("a"), Tag("b")), Tag("c")), "a | b | c")
    }

    @Test
    fun `and operator has higher precedence than or operator`() {
        assertParsedAs(Or(Tag("a"), And(Tag("b"), Tag("c"))), "a | b & c")
        assertParsedAs(Or(And(Tag("a"), Tag("b")), Tag("c")), "a & b | c")
    }

    @Test
    fun `not operator has higher precedence than and operator`() {
        assertParsedAs(And(Not(Tag("a")), Tag("b")), "!a & b")
    }

    @Test
    fun `parentheses override precedence`() {
        assertParsedAs(And(Or(Tag("a"), Tag("b")), Tag("c")), "(a | b) & c")
        assertParsedAs(Not(Or(Tag("a"), Tag("b"))), "!(a | b)")
        assertParsedAs(Tag("a"), "((a))")
    }

    @Test
    fun `any() and none() reserved expressions`() {
        assertParsedAs(AnyTag, "any()")
        assertParsedAs(NoTags, "none()")
        // JUnit matches these reserved expressions in a case-insensitive way
        assertParsedAs(AnyTag, "ANY()")
        assertParsedAs(NoTags, "None()")
        assertParsedAs(And(AnyTag, Not(NoTags)), "any() & !none()")
    }

    @Test
    fun `tags that only start like reserved expressions are regular tags`() {
        assertParsedAs(Tag("anything"), "anything")
        assertParsedAs(Tag("none"), "none")
    }

    @Test
    fun `blank expressions are rejected`() {
        assertRejected("")
        assertRejected("   ")
    }

    @Test
    fun `expressions with missing operands are rejected`() {
        assertRejected("!")
        assertRejected("a &")
        assertRejected("& a")
        assertRejected("a |")
        assertRejected("| a")
        assertRejected("a & !")
    }

    @Test
    fun `expressions with missing operators are rejected`() {
        assertRejected("a b")
        assertRejected("a !b")
        assertRejected("(a) (b)")
    }

    @Test
    fun `double operators are rejected (as in JUnit)`() {
        assertRejected("a && b")
        assertRejected("a || b")
    }

    @Test
    fun `unbalanced parentheses are rejected`() {
        assertRejected("(a")
        assertRejected("a)")
        assertRejected("(a & (b | c)")
        assertRejected("()")
    }

    @Test
    fun `tag names with reserved characters are rejected`() {
        assertRejected("a,b")
    }

    private fun assertParsedAs(expected: JUnitTagExpression, expression: String) {
        assertEquals(expected, JUnitTagExpression.parse(expression), "unexpected parse result for '$expression'")
    }

    private fun assertRejected(expression: String) {
        assertFailsWith<JUnitTagExpressionSyntaxException>("expected '$expression' to be rejected") {
            JUnitTagExpression.parse(expression)
        }
    }
}
