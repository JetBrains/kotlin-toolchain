/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.core

import kotlinx.coroutines.flow.*

class Catalog(items: Iterable<Item>) {
    private val itemsById: Map<Int, Item> = items.associateBy { it.id }

    val items: Collection<Item> get() = itemsById.values

    operator fun get(id: Int): Item? = itemsById[id]

    fun withTag(tag: String): List<Item> = items.filter { tag in it.tags }.sortedBy { it.name }

    fun cheapest(count: Int): List<Item> = items.sortedBy { it.price }.take(count)

    fun total(discount: Discount = Discount.None): Price =
        items.fold(Price(0)) { acc, item -> acc + discount.apply(item.price) }

    fun stream(): Flow<Item> = flow {
        for (item in items.sortedBy { it.id }) emit(item)
    }
}

inline fun <T, R : Comparable<R>> Iterable<T>.maxOfOrDefault(default: R, selector: (T) -> R): R =
    maxOfOrNull(selector) ?: default

fun Iterable<Item>.groupByFirstTag(): Map<String, List<Item>> =
    groupBy { it.tags.minOrNull() ?: "untagged" }

fun describe(item: Item): String = when {
    item.tags.isEmpty() -> "${item.name} (${item.price})"
    else -> "${item.name} (${item.price}) [${item.tags.sorted().joinToString()}]"
}
