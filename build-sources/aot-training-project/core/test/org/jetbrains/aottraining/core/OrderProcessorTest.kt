/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class OrderProcessorTest {
    private val pen = Item(1, "Pen", Price(150))

    @Test
    fun `valid order is accepted`() = runTest {
        val result = OrderProcessor().process(Order("o-1", listOf(OrderLine(pen, 2))))
        assertIs<OrderResult.Accepted>(result)
    }

    @Test
    fun `oversized order is rejected`() = runTest {
        val result = OrderProcessor().process(Order("o-2", listOf(OrderLine(pen, 1000))))
        assertIs<OrderResult.Rejected>(result)
    }
}
