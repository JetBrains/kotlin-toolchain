/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogTest {
    private val catalog = Catalog(
        listOf(
            Item(1, "Pen", Price(150), setOf("office")),
            Item(2, "Notebook", Price(499), setOf("office", "paper")),
            Item(3, "Mug", Price(899)),
        )
    )

    @Test
    fun `total with percentage discount`() {
        assertEquals(Price(1303), catalog.total(Discount.Percentage(10)))
    }

    @Test
    fun `items are streamed by id`() = runTest {
        assertEquals(listOf(1, 2, 3), catalog.stream().toList().map { it.id })
    }

    @Test
    fun `cheapest items`() {
        assertEquals(listOf("Pen", "Notebook"), catalog.cheapest(2).map { it.name })
    }
}
