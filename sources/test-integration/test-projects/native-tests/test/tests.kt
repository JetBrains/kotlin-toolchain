/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import kotlin.test.*

class MyTest {
    @Test
    fun testSucceed() {
        println("running testSucceed")
    }

    @Test
    fun testFailure() {
        println("running testFailure")
        assertEquals(2 + 2, 5)
    }

    @Ignore
    @Test
    fun testIgnored() {
        println("shouldn't run testIgnored")
    }
}