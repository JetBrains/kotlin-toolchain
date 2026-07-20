/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package io.github.kotlin.crypto.cinterop

import kotlin.test.Test

class CryptoCinteropUtilTest {

    @Test
    fun `test getLinuxRandom`() {
        val random = getLinuxRandom()
    }
}