package org.jetbrains.kotlintoolchain.kmp.sample.fibonacci

import org.jetbrains.kotlintoolchain.kmp.sample.platform.getPosixPathMax
import kotlin.test.Test
import kotlin.test.assertTrue

class WindowsPlatformUtilTest {

    @Test
    fun `test posix PATH_MAX`() {
        assertTrue(getPosixPathMax() > 0)
    }
}