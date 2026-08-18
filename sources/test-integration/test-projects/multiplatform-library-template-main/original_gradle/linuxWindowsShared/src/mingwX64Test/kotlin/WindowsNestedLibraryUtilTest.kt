/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.fibonacci

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsNestedLibraryUtilTest {

    @Test
    fun `test linuxBar`() {
        assertEquals(6, nestedBar())
    }
}
