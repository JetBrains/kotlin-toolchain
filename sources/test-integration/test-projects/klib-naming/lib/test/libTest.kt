package org.example.lib

import kotlin.test.Test
import kotlin.test.assertEquals

class LibTest {
    @Test
    fun greetingIsCorrect() {
        assertEquals("Hello from lib", greeting())
    }
}
