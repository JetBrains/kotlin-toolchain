/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.test.FilterMode
import org.jetbrains.amper.test.TestFilter
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `tag filters are ignored`() {
        assertEquals(
            listOf("--ktest_logger=teamcity"),
            nativeArgsWithFilters(
                TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Include),
                TestFilter.includeOrExcludeTag(tagExpression = "flaky", mode = FilterMode.Exclude),
            ),
        )
    }

    @Test
    fun `tag filters are ignored but other filters are still applied`() {
        assertEquals(
            listOf("--ktest_logger=teamcity", "--ktest_filter=com.example.MyTest.*"),
            nativeArgsWithFilters(
                TestFilter.includeOrExcludeSuite(pattern = "com.example.MyTest", mode = FilterMode.Include),
                TestFilter.includeOrExcludeTag(tagExpression = "slow", mode = FilterMode.Include),
            ),
        )
    }

    private fun nativeArgsWithFilters(vararg filters: TestFilter): List<String> =
        AllRunSettings(testFilters = filters.toList()).toNativeTestExecutableArgs()
}
