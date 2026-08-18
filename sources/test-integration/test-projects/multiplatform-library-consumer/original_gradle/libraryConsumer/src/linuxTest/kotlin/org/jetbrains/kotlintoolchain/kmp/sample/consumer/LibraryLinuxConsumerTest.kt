/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.consumer

import org.jetbrains.kotlintoolchain.kmp.sample.fibonacci.generateFibi
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryLinuxConsumerTest {

    @Test
    fun `test 3rd element`() {
        assertEquals(8, generateFibi().take(3).last())
    }
}