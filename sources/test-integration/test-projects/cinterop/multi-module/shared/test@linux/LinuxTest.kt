import com.example.native.custom.getGreeting
import com.example.native.custom.getGreetingLinux
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.expect

class LinuxTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun doTest() {
        expect("Hello From Native") {
            getGreeting(1u)?.toKString()
        }
        expect("Hello From Linux") {
            getGreetingLinux(1u)?.toKString()
        }
    }
}
