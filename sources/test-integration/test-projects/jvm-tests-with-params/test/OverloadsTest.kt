/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package com.example.testswithparams

import kotlin.test.*
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

class OverloadsTest {

    // test names are intentionally the same

    @Test
    fun test() {
        println("running OverloadsTest.test()")
    }

    @Test
    fun test(testInfo: TestInfo) {
        println("running OverloadsTest.test(TestInfo)")
    }

    @Test
    fun test(testInfo: TestInfo, testReporter: TestReporter) {
        println("running OverloadsTest.test(TestInfo, TestReporter)")
    }

    class NestedTest {
        @Test
        @ExtendWith(NestedArgumentResolver::class)
        fun test(nestedArgument: NestedArgument) {
            println("running OverloadsTest.NestedTest.test(NestedArgument)")
        }
    }

    class NestedArgument

    class NestedArgumentResolver : ParameterResolver {
        override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext) =
            parameterContext.parameter.type == NestedArgument::class.java

        override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext) = NestedArgument()
    }
}
