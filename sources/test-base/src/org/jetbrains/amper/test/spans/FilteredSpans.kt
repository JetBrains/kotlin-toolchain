/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.spans

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

data class FilteredSpans(
    private val matchingSpans: List<SpanData>,
    private val filters: List<String>,
) {
    private val filtersDescription: String = filters.joinToString()

    fun filter(description: String, predicate: (SpanData) -> Boolean): FilteredSpans = copy(
        matchingSpans = matchingSpans.filter(predicate),
        filters = filters + description,
    )

    fun assertSingle(): SpanData = assertTimes(1).single()

    private fun SpanData.format() = "$name[\n${attributes.asMap().toSortedMap(compareBy { attr -> attr.key }).entries.joinToString("\n") { "  $it" }}\n]"

    fun assertTimes(times: Int): List<SpanData> {
        assertFalse(matchingSpans.isEmpty(), "No span matching the filters: $filtersDescription")
        assertEquals(times, matchingSpans.size, "${matchingSpans.size} spans (instead of $times) matching the filters: $filtersDescription\n" +
                matchingSpans.joinToString("\n") { it.format() })
        return matchingSpans
    }

    fun assertNone() {
        assertTrue(
            matchingSpans.isEmpty(),
            "Got ${matchingSpans.size} but expected no spans matching the filters: $filtersDescription"
        )
    }

    fun all() = matchingSpans
}

fun SpansTestCollector.spansNamed(name: String): FilteredSpans = filteredSpans.withName(name)

private val SpansTestCollector.filteredSpans: FilteredSpans
    get() = FilteredSpans(spans, emptyList())

fun FilteredSpans.withName(name: String): FilteredSpans = filter("name='$name'") { it.name == name }

fun <T> FilteredSpans.withAttribute(key: AttributeKey<T>, value: T): FilteredSpans =
    filter("attr['${key.key}']='$value'") { it.attributes[key] == value }

val SpansTestCollector.kotlinJvmCompilationSpans: FilteredSpans
    get() = spansNamed("kotlin-compilation")

val SpansTestCollector.javaCompilationSpans: FilteredSpans
    get() = spansNamed("javac")

val SpansTestCollector.kotlinNativeCompilationSpans: FilteredSpans
    get() = spansNamed("konanc")

fun FilteredSpans.withAmperModule(name: String) = withAttribute(amperModuleKey, name)

fun FilteredSpans.withFragment(name: String) = withAttribute(fragmentsKey, [name])
