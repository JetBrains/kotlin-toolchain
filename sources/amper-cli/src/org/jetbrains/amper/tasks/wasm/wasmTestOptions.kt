/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.test.FilterMode
import org.jetbrains.amper.test.TestFilter

internal fun List<TestFilter>.toTestFilterArgs(): List<String> {
    if (isEmpty()) {
        return []
    }
    val filters = mapNotNull { it.toKotlinWasmJsTestFilter() }
    val includeFilters = filters.filter { it.mode == FilterMode.Include }
    val excludeFilters = filters.filter { it.mode == FilterMode.Exclude }

    return buildList {
        if (includeFilters.isNotEmpty()) {
            add("--include")
            add(includeFilters.joinToString(",") { it.pattern })
        }
        if (excludeFilters.isNotEmpty()) {
            add("--exclude")
            add(excludeFilters.joinToString(",") { it.pattern })
        }
    }
}

private data class WebBasedTestFilter(val pattern: String, val mode: FilterMode)

// See: https://github.com/JetBrains/kotlin/blob/3149a575b8af05df6a47e7d5abf1e6689b258d06/libraries/kotlin.test/wasm/src/main/kotlin/kotlin/test/FrameworkTestArguments.kt
private fun TestFilter.toKotlinWasmJsTestFilter(): WebBasedTestFilter? = when (this) {
    is TestFilter.SpecificTestInclude -> WebBasedTestFilter(
        pattern = toKotlinWasmJsFormat(),
        mode = FilterMode.Include,
    )
    is TestFilter.SpecificSuiteInclude -> WebBasedTestFilter(
        pattern = fullyQualifiedName.replace('/', '.'),
        mode = FilterMode.Include,
    )
    is TestFilter.SuitePattern -> WebBasedTestFilter(
        pattern = pattern.replace('/', '.').also {
            if ("?" in it) {
                userReadableError("Kotlin/Wasm tests don't support '?' in test filters, use '*' instead")
            }
            if (it[0].isUpperCase()) {
                userReadableError("Kotlin/Wasm tests don't support pattern filters starting with an uppercase " +
                        "letter: '$pattern'.\nPrepend with '*' if it's acceptable to match other prefixes.")
            }
        } + ".*", // patterns match full tests
        mode = mode,
    )
    // Kotlin/Wasm tests have no notion of tags, so tag filters cannot be expressed as native test filters.
    // They are instead taken into account as a whole to decide whether to run the test executable at all,
    // see tagFiltersWouldMatchUntaggedTests().
    is TestFilter.TagExpression -> null
}

private fun TestFilter.SpecificTestInclude.toKotlinWasmJsFormat(): String {
    val nestedClassSuffix = if (nestedClassName != null) ".$nestedClassName" else ""
    return "$suiteFqn$nestedClassSuffix.$testName"
}
