import platform.posix.getenv
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetTest {
    @Test
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun test() {
        assertEquals(
            getenv("EXPECTED_OUTPUT")?.toKString(),
            swiftPMImport.linkage.tests.ObjCCompatibleSwift().helloFromSwift(),
        )
    }
}