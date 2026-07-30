import com.example.native.custom.getGreeting
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.expect

class NativeTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun doTest() {
        expect("Hello From Native") {
            getGreeting(1u)?.toKString()
        }
    }
}
