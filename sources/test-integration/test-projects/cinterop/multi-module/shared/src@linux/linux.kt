import com.example.native.custom.getGreeting
import com.example.native.custom.getGreetingLinux
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
fun linux() {
    getGreeting(1u)
    getGreetingLinux(1u)
}