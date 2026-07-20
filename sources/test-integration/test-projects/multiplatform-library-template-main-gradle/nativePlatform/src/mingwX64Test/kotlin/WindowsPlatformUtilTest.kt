package io.github.kotlin.fibonacci

import io.github.kotlin.platform.getPosixPathMax
import kotlin.test.Test
import kotlin.test.assertTrue

class WindowsPlatformUtilTest {

    @Test
    fun `test posix PATH_MAX`() {
        assertTrue(getPosixPathMax() > 0)
    }
}