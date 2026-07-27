/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.consumer

import org.jetbrains.kotlintoolchain.kmp.sample.platform.getPosixPathMax
import kotlin.test.Test
import kotlin.test.assertTrue

class NativePlatformMingwX64ConsumerTest {

    /**
     * This test checks that [getPosixPathMax] is correctly resolved from the library dependency
     */
    @Test
    fun `test getPosixPathMax`() {
        assertTrue(getPosixPathMax() > 0)
    }
}