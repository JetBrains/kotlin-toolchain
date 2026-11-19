package org.jetbrains.kotlintoolchain.kmp.sample.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformUtilTest {

    @Test
    fun `test posix PATH_MAX`() {
        assertTrue(getPosixPathMax() > 0)
    }
}