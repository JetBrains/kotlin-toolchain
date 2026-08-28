/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.junit.tags.JUnitTagExpression
import org.jetbrains.amper.junit.tags.JUnitTagExpressionSyntaxException
import org.jetbrains.amper.junit.tags.matches
import org.slf4j.LoggerFactory

enum class FilterMode {
    Include, Exclude
}

sealed class TestFilter {

    /**
     * A filter that includes a specific test method.
     */
    data class SpecificTestInclude(
        /**
         * The fully qualified name of the class or top-level test suite function containing the test to match.
         * Nested classes are separated from their containing class using the '/' separator.
         */
        val suiteFqn: String,
        /**
         * The simple name of the nested class (under [suiteFqn]) that contains the test to match, if any.
         */
        val nestedClassName: String?,
        /**
         * The simple name of the test method/function to match, excluding the containing class and package.
         */
        val testName: String,
        /**
         * The list of parameter types of the test method (for parameterized tests, or tests with injected services).
         */
        val paramTypes: List<String>?,
    ): TestFilter()

    /**
     * A filter that includes a specific test class or top-level test suite function.
     */
    data class SpecificSuiteInclude(
        /**
         * The fully qualified name of the class or top-level test suite function.
         * Nested classes are separated from their containing class using the '/' separator.
         *
         * Note: Kotlin identifiers may contain spaces and other symbols like `^$(){}+-=_#%&`.
         * Therefore, they should be properly escaped when used in regexes.
         */
        val fullyQualifiedName: String,
    ): TestFilter()

    /**
     * A filter that includes or excludes entire test classes or top-level test suite functions by pattern matching.
     */
    data class SuitePattern(
        /**
         * The fully qualified name of the class or top-level test suite function.
         * Nested classes are separated from their containing class using the '/' separator.
         * May contain '*' to represent any group of characters or '?' to represent a single character.
         *
         * Note: Kotlin identifiers may contain spaces and other symbols like `^$(){}+-=_#%&`.
         * Therefore, they should be properly escaped when used in regexes.
         * `*` and `?` are also technically allowed, but we rely on their special meaning here and they should not be
         * treated literally.
         */
        val pattern: String,
        /**
         * Whether this filter should include or exclude what it matches.
         */
        val mode: FilterMode,
    ): TestFilter()

    /**
     * A filter that includes or excludes tests based on the tags they are annotated with.
     *
     * Tags only exist in JVM tests (including Android), so tests of other platforms are all considered untagged.
     * This also means only JVM tests can be filtered by tag by the test framework itself. For Kotlin/Native tests,
     * we can only decide whether to run all tests of a module or none of them, depending on whether untagged tests
     * match this filter (see [wouldMatchUntaggedTest]).
     */
    data class TagExpression(
        /**
         * A JUnit tag expression, which can be a single tag name, or a boolean expression combining tag names with
         * the `!`, `&`, and `|` operators (and parentheses for grouping).
         * See https://docs.junit.org/6.1.3/running-tests/tags.html#expressions
         */
        val expression: String,
        /**
         * Whether this filter should include or exclude what it matches.
         */
        val mode: FilterMode,
    ): TestFilter() {
        /**
         * The parsed [expression], which allows reasoning about the tests that this filter matches.
         */
        val parsedExpression: JUnitTagExpression = try {
            JUnitTagExpression.parse(expression)
        } catch (e: JUnitTagExpressionSyntaxException) {
            userReadableError(e.message, e)
        }
    }

    companion object {

        private val logger = LoggerFactory.getLogger(TestFilter::class.java)

        /**
         * A regex to parse the value of --include-test.
         *
         * **WARNING**: Kotlin identifiers may contain spaces and other symbols like `_-(){}'",=+|*&^%$#@!~€` (when the
         * name is enclosed in backticks). This regex is therefore lenient on purpose.
         * Forbidden characters (at least on JVM) are `.<>:/[]` and the backtick.
         *
         * Examples of weird stuff that is allowed:
         * * a class name can contain parentheses or dollars
         * * a method name can end with `(something)`, it happens in real life and can be confused with a param list
         */
        // TODO maybe we could eliminate some of the weirdness if we asked users to use `backticks` in weird cases
        private val testFqnRegex = Regex("""(?<suiteFqn>[^/]+?)(/(?<nestedClass>[^.]+))?\.(?<method>[^.`]+?)(\((?<params>[^)]*)\))?""")

        fun includeTest(testFqn: String): TestFilter {
            if ('*' in testFqn || '?' in testFqn) {
                logger.warn("When matching a specific test method, '*' and '?' are treated literally.")
            }
            val match = requireNotNull(testFqnRegex.matchEntire(testFqn)) {
                "invalid test name '$testFqn'. Expected a fully qualified method name including the package and class name. " +
                        "Nested classes, if present, should be separated from the containing class using the '/' separator." +
                        "The name should be literal, without wildcards."
            }
            return SpecificTestInclude(
                suiteFqn = match.groups["suiteFqn"]?.value
                    ?: error("Internal error: 'suiteFqn' group should always be present in the regex match result"),
                nestedClassName = match.groups["nestedClass"]?.value,
                testName = match.groups["method"]?.value
                    ?: error("Internal error: 'method' group should always be present in the regex match result"),
                paramTypes = match.groups["params"]?.value
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() },
            )
        }

        fun includeOrExcludeSuite(pattern: String, mode: FilterMode): TestFilter =
            if (mode == FilterMode.Exclude || "*" in pattern || "?" in pattern) {
                SuitePattern(pattern = pattern, mode = mode)
            } else {
                SpecificSuiteInclude(fullyQualifiedName = pattern)
            }

        fun includeOrExcludeTag(tagExpression: String, mode: FilterMode): TestFilter =
            // The constructor parses the expression, and thus rejects invalid ones (with a user-friendly message).
            TagExpression(expression = tagExpression, mode = mode)
    }
}

/**
 * Returns whether tests without any tag would be run, considering the tag filters in this list of test filters.
 *
 * Following JUnit's behavior for repeated tag options, tests must match at least one of the include expressions
 * (if any), and must not match any of the exclude expressions.
 */
internal fun List<TestFilter>.tagFiltersWouldMatchUntaggedTests(): Boolean {
    val [includeFilters, excludeFilters] = filterIsInstance<TestFilter.TagExpression>()
        .partition { it.mode == FilterMode.Include }
    return (includeFilters.isEmpty() || includeFilters.any { it.wouldMatchUntaggedTest() })
            && excludeFilters.none { it.wouldMatchUntaggedTest() }
}

/**
 * Returns whether a test without any tag would match this [TagExpression].
 *
 * This is useful for platforms that have no notion of test tags (all their tests are untagged).
 */
private fun TestFilter.TagExpression.wouldMatchUntaggedTest(): Boolean =
    parsedExpression.matches(tags = [])

internal fun String.wildcardsToRegex(): String {
    val wildcardPattern = this
    var prevIndex = 0
    return buildString {
        wildcardPattern.forEachIndexed { index, c ->
            if (c == '*' || c == '?') {
                append(wildcardPattern.literalSubstring(prevIndex until index))
                append(if (c == '*') ".*" else ".")
                prevIndex = index + 1
            }
        }
        append(wildcardPattern.literalSubstring(prevIndex until wildcardPattern.length))
    }
}

private fun String.literalSubstring(range: IntRange) =
    substring(range).let { if (it.isNotEmpty()) Regex.escape(it) else "" }
