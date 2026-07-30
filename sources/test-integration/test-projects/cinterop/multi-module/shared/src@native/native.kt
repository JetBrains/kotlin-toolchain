import com.example.native.custom.getGreeting
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
fun native() {
    getGreeting(1u)
}
