/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.example.shared

import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Tags only exist on the JVM, so these JVM-only tests are the only tagged tests of this multiplatform module.
 * They allow testing tag filters that match some JVM tests while excluding all (untagged) Kotlin/Native tests.
 */
class JvmTaggedTest {

    @Test
    @Tag("slow")
    fun slowJvmTest() {
        println("running JvmTaggedTest.slowJvmTest")
    }

    @Test
    @Tag("fast")
    fun fastJvmTest() {
        println("running JvmTaggedTest.fastJvmTest")
    }
}
