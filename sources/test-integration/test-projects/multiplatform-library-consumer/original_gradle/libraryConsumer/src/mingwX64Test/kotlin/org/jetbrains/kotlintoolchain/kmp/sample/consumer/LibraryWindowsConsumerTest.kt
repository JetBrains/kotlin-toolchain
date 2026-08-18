/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.consumer

import org.jetbrains.kotlintoolchain.kmp.sample.fibonacci.generateFibi
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryWindowsConsumerTest {

    /**
     * This test checks that [generateFibi] is correctly resolved from the library dependency
     */
    @Test
    fun `test 3rd element`() {
        assertEquals(9, generateFibi().take(3).last())
    }
}