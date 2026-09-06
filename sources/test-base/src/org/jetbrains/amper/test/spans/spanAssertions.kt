/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.spans

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import kotlin.test.assertEquals
import kotlin.test.fail

val amperModuleKey: AttributeKey<String> = AttributeKey.stringKey("amper-module")

val fragmentsKey: AttributeKey<List<String>> = AttributeKey.stringArrayKey("fragments")

fun SpanData.assertHasModule(moduleName: String) {
    assertHasAttribute(amperModuleKey, moduleName)
}

fun <T> SpanData.assertHasAttribute(key: AttributeKey<T>, value: T) {
    val actualValue = attributes[key]
        ?: fail("Attribute '$key' is missing in span '$name'")
    assertEquals(value, actualValue, "Wrong value for attribute '$key' in span $name: expected '$value' but was '$actualValue'")
}

fun SpansTestCollector.assertSingleKotlinJvmCompilationSpan(assertions: CompilationSpanAssertions.() -> Unit = {}) {
    kotlinJvmCompilationSpans.assertSingleKotlinCompilation(assertions)
}

/**
 * Asserts things about the single Kotlin compilation span matching the filters of these [FilteredSpans].
 */
fun FilteredSpans.assertSingleKotlinCompilation(assertions: CompilationSpanAssertions.() -> Unit = {}) {
    CompilationSpanAssertions(assertSingle(), "compiler-args").assertions()
}

fun SpansTestCollector.assertEachKotlinJvmCompilationSpan(assertions: CompilationSpanAssertions.() -> Unit = {}) {
    kotlinJvmCompilationSpans.all().forEach { kotlinSpan ->
        CompilationSpanAssertions(kotlinSpan, "compiler-args").assertions()
    }
}

fun SpansTestCollector.assertJavaCompilationSpan(assertions: CompilationSpanAssertions.() -> Unit = {}) {
    val javacSpan = javaCompilationSpans.assertSingle()
    CompilationSpanAssertions(javacSpan, "args").assertions()
}

fun SpansTestCollector.assertEachKotlinNativeCompilationSpan(assertions: CompilationSpanAssertions.() -> Unit = {}) {
    kotlinNativeCompilationSpans.all().forEach { kotlinSpan ->
        CompilationSpanAssertions(kotlinSpan, "args").assertions()
    }
}