/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package io.github.kotlin.ctypto

import org.kotlincrypto.random.RandomnessProcurementException
import kotlin.test.Test
import kotlin.test.fail

class CryptoUtilTest {

    @Test
    fun `test throwRandomnessProcurementException`() {
        try {
            throwRandomnessProcurementException()
            fail("Exception should has been thrown")
        } catch(_: RandomnessProcurementException) {
            // It is expected to be here.
        }
    }
}