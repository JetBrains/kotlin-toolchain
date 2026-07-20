/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.kotlin.fibonacci

import kotlin.test.Test
import kotlin.test.assertEquals

class WasmJsTest {

    @Test
    fun `test wasmJs number`() {
        assertEquals(2, platformSpecificElement)
    }
}
