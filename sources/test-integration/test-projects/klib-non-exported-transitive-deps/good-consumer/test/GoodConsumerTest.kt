import kotlin.test.Test
import kotlin.test.assertEquals

class GoodConsumerTest {
    @Test
    fun consumeUsesBothFacades() {
        assertEquals("hiddenexported", consume())
    }
}
