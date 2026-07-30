import com.example.native.custom.getGreeting
import com.example.native.custom.getGreetingLinux
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
fun linuxArm64() {
    getGreeting(1u)
    getGreetingLinux(1u)
}