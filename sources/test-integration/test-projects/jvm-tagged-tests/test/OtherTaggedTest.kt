/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package com.example.taggedtests

import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Uses the same tags as [TaggedTest], so we can test the interaction between class filters and tag filters.
 */
class OtherTaggedTest {

    @Test
    @Tag("slow")
    fun slowTest() {
        println("running OtherTaggedTest.slowTest")
    }

    @Test
    @Tag("fast")
    fun fastTest() {
        println("running OtherTaggedTest.fastTest")
    }

    @Test
    fun untaggedTest() {
        println("running OtherTaggedTest.untaggedTest")
    }
}
