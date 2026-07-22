/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.test.sample.sample

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmTest {

    @Test
    fun `test android number`() {
        assertEquals(2, platformSpecificElement)
    }
}
