import io.github.kotlin.platform.getPosixPathMax
import kotlin.test.Test
import kotlin.test.assertTrue

class LinuxPlatformUtilTest {

    @Test
    fun `test posix PATH_MAX`() {
        assertTrue(getPosixPathMax() > 0)
    }
}