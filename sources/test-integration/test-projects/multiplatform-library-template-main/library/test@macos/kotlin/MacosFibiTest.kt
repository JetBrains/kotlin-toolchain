package org.jetbrains.kotlintoolchain.kmp.test.sample.fibonacci

import kotlin.test.Test
import kotlin.test.assertEquals

class MacosFibiTest {

    @Test
    fun `test 3rd element`() {
        assertEquals(21, generateFibi().take(3).last())
    }
}
