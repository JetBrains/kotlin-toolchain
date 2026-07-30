import com.example.native.custom.getGreeting
import com.example.native.custom.getGreetingApple
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
fun macos() {
    getGreeting(1u)
    getGreetingApple(2u)
}
