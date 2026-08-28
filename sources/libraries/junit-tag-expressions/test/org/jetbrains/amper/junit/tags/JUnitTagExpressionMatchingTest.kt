/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.tags

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JUnitTagExpressionMatchingTest {

    @Test
    fun `single tag`() {
        assertMatches("slow", tags = ["slow"])
        assertMatches("slow", tags = ["slow", "flaky"])
        assertDoesNotMatch("slow", tags = ["flaky"])
        assertDoesNotMatch("slow", tags = [])
    }

    @Test
    fun `not operator`() {
        assertMatches("!slow", tags = ["flaky"])
        assertMatches("!slow", tags = [])
        assertDoesNotMatch("!slow", tags = ["slow"])
    }

    @Test
    fun `and operator`() {
        assertMatches("slow & flaky", tags = ["slow", "flaky"])
        assertDoesNotMatch("slow & flaky", tags = ["slow"])
        assertDoesNotMatch("slow & flaky", tags = [])
    }

    @Test
    fun `or operator`() {
        assertMatches("slow | flaky", tags = ["slow"])
        assertMatches("slow | flaky", tags = ["flaky"])
        assertDoesNotMatch("slow | flaky", tags = ["fast"])
        assertDoesNotMatch("slow | flaky", tags = [])
    }

    @Test
    fun `combined operators`() {
        assertMatches("slow & !flaky", tags = ["slow"])
        assertDoesNotMatch("slow & !flaky", tags = ["slow", "flaky"])
        assertMatches("(slow | fast) & !flaky", tags = ["fast"])
        assertDoesNotMatch("(slow | fast) & !flaky", tags = ["fast", "flaky"])
    }

    @Test
    fun `any() matches tests with at least one tag`() {
        assertMatches("any()", tags = ["slow"])
        assertDoesNotMatch("any()", tags = [])
        assertDoesNotMatch("!any()", tags = ["slow"])
        assertMatches("!any()", tags = [])
    }

    @Test
    fun `none() matches tests without tags`() {
        assertMatches("none()", tags = [])
        assertDoesNotMatch("none()", tags = ["slow"])
        assertDoesNotMatch("!none()", tags = [])
        assertMatches("!none()", tags = ["slow"])
    }

    private fun assertMatches(expression: String, tags: Set<String>) {
        assertTrue(JUnitTagExpression.parse(expression).matches(tags), "'$expression' should match tags $tags")
    }

    private fun assertDoesNotMatch(expression: String, tags: Set<String>) {
        assertFalse(JUnitTagExpression.parse(expression).matches(tags), "'$expression' should not match tags $tags")
    }
}
