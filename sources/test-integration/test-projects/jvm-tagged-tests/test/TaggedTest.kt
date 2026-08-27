/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package com.example.taggedtests

import org.junit.jupiter.api.Tag
import kotlin.test.Test

class TaggedTest {

    @Test
    @Tag("slow")
    fun slowTest() {
        println("running TaggedTest.slowTest")
    }

    @Test
    @Tag("slow")
    @Tag("flaky")
    fun slowFlakyTest() {
        println("running TaggedTest.slowFlakyTest")
    }

    @Test
    @Tag("fast")
    fun fastTest() {
        println("running TaggedTest.fastTest")
    }

    @Test
    fun untaggedTest() {
        println("running TaggedTest.untaggedTest")
    }
}
