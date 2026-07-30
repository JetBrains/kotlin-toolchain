import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertTrue

class MyTest {
    @Test
    fun testHelloWorld() {
        println("running testHelloWorld")
        assertTrue(true)
    }

    class MyNestedTest {
        @Test
        fun testFalsyHelloWorld() {
            println("running testFalsyHelloWorld")
            assertTrue(false)
        }
    }

    @Test
    @Ignore
    fun testIgnoredHelloWorld() {
        println("running testIgnoredHelloWorld")
        assertTrue(false)
    }
}