package io.github.kotlin.fibonacci

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsFibiTest {

    @Test
    fun `test 3rd element`() {
        assertEquals(9, generateFibi().take(3).last())
    }
}
