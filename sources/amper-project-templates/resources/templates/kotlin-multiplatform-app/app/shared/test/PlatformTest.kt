package {{PROJECT_ID}}

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun `platform name is reported`() {
        assertTrue(getPlatform().name.isNotBlank())
    }

    @Test
    fun `greeting is built from the platform name`() {
        assertEquals("Hello, ${getPlatform().name}!", Greeting().greet())
    }
}
