/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import org.jetbrains.amper.test.FilterMode
import org.jetbrains.amper.test.TestFilter

internal fun List<TestFilter>.toTestFilterArg(): String? {
    if (isEmpty()) {
        return null
    }
    val filters = mapNotNull { it.toKotlinWasmJsTestFilter() }
    val includeFilters = filters
        .filter { it.mode == FilterMode.Include }
        .joinToString(",") { it.pattern }
    val excludeFilters = filters
        .filter { it.mode == FilterMode.Exclude }
        .joinToString(",") { it.pattern }

    return buildString {
        if (includeFilters.isNotEmpty()) {
            append("--include")
            append(includeFilters)
        }
        if (excludeFilters.isNotEmpty()) {
            append("--exclude")
            append(excludeFilters)
        }
    }
}

private data class WebBasedTestFilter(val pattern: String, val mode: FilterMode)

private fun TestFilter.toKotlinWasmJsTestFilter(): WebBasedTestFilter? = when (this) {
    is TestFilter.SpecificTestInclude -> WebBasedTestFilter(
        pattern = toKotlinWasmJsFormat(),
        mode = FilterMode.Include,
    )
    is TestFilter.SpecificSuiteInclude -> WebBasedTestFilter(
        pattern = "${fullyQualifiedName.replace('/', '.')}.*",
        mode = FilterMode.Include,
    )
    is TestFilter.SuitePattern -> WebBasedTestFilter(
        pattern = pattern.replace('/', '.'),
        mode = mode,
    )
    // Kotlin/Wasm tests have no notion of tags, so tag filters cannot be expressed as native test filters.
    // They are instead taken into account as a whole to decide whether to run the test executable at all,
    // see shouldRunNativeTests().
    is TestFilter.TagExpression -> null
}

private fun TestFilter.SpecificTestInclude.toKotlinWasmJsFormat(): String {
    val nestedClassSuffix = if (nestedClassName != null) ".$nestedClassName" else ""
    return "$suiteFqn$nestedClassSuffix.$testName"
}
