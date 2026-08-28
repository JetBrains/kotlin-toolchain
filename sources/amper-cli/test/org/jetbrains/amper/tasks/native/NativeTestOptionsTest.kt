/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.test.FilterMode
import org.jetbrains.amper.test.TestFilter
import org.jetbrains.amper.test.tagFiltersWouldMatchUntaggedTests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeTestOptionsTest {

    @Test
    fun `no filters means no ktest_filter arg`() {
        assertEquals(listOf("--ktest_logger=teamcity"), nativeArgsWithFilters())
    }

    @Test
    fun `suite patterns are translated to ktest_filter`() {
        assertEquals(
            listOf("--ktest_logger=teamcity", "--ktest_filter=com.example.MyTest.*-com.example.OtherTest.*"),
            nativeArgsWithFilters(
                TestFilter.includeOrExcludeSuite(pattern = "com.example.MyTest", mode = FilterMode.Include),
                TestFilter.includeOrExcludeSuite(pattern = "com.example.OtherTest", mode = FilterMode.Exclude),
            ),
        )
    }

    @Test
    fun `tag filters are not translated to ktest_filter`() {
        assertEquals(
            listOf("--ktest_logger=teamcity"),
            nativeArgsWithFilters(
                TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Include),
                TestFilter.includeOrExcludeTag(tagExpression = "flaky", mode = FilterMode.Exclude),
            ),
        )
    }

    @Test
    fun `tag filters are not translated to ktest_filter but other filters are still applied`() {
        assertEquals(
            listOf("--ktest_logger=teamcity", "--ktest_filter=com.example.MyTest.*"),
            nativeArgsWithFilters(
                TestFilter.includeOrExcludeSuite(pattern = "com.example.MyTest", mode = FilterMode.Include),
                TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Include),
            ),
        )
    }

    @Test
    fun `tests should run when there are no tag filters`() {
        assertTrue(shouldRunTestsWithFilters())
        assertTrue(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeSuite(pattern = "com.example.MyTest", mode = FilterMode.Include),
        ))
    }

    @Test
    fun `tests should not run when include tag filters cannot match untagged tests`() {
        assertFalse(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Include),
        ))
        assertFalse(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "slow & !flaky", mode = FilterMode.Include),
        ))
        assertFalse(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "any()", mode = FilterMode.Include),
        ))
    }

    @Test
    fun `tests should run when an include tag filter matches untagged tests`() {
        assertTrue(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "!slow", mode = FilterMode.Include),
        ))
        assertTrue(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "none()", mode = FilterMode.Include),
        ))
        // include filters are combined with OR semantics, so it's enough for one of them to match
        assertTrue(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Include),
            TestFilter.includeOrExcludeTag(tagExpression = "!flaky", mode = FilterMode.Include),
        ))
    }

    @Test
    fun `tests should not run when an exclude tag filter matches untagged tests`() {
        assertFalse(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "!slow", mode = FilterMode.Exclude),
        ))
        assertFalse(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "none()", mode = FilterMode.Exclude),
        ))
        // a test is excluded as soon as it matches one of the exclude filters
        assertFalse(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Exclude),
            TestFilter.includeOrExcludeTag(tagExpression = "!flaky", mode = FilterMode.Exclude),
        ))
    }

    @Test
    fun `tests should run when exclude tag filters cannot match untagged tests`() {
        assertTrue(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Exclude),
        ))
        assertTrue(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "any()", mode = FilterMode.Exclude),
        ))
    }

    @Test
    fun `exclude tag filters take precedence over include tag filters`() {
        assertFalse(shouldRunTestsWithFilters(
            TestFilter.includeOrExcludeTag(tagExpression = "!slow", mode = FilterMode.Include),
            TestFilter.includeOrExcludeTag(tagExpression = "!flaky", mode = FilterMode.Exclude),
        ))
    }

    private fun nativeArgsWithFilters(vararg filters: TestFilter): List<String> =
        AllRunSettings(testFilters = filters.toList()).toNativeTestExecutableArgs()

    private fun shouldRunTestsWithFilters(vararg filters: TestFilter): Boolean =
        filters.toList().tagFiltersWouldMatchUntaggedTests()
}
