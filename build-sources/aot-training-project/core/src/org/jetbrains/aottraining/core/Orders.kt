/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.core

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class Order(val id: String, val lines: List<OrderLine>, val discount: Discount = Discount.None) {
    val total: Price
        get() = discount.apply(lines.fold(Price(0)) { acc, line -> acc + line.total })
}

@Serializable
data class OrderLine(val item: Item, val quantity: Int) {
    init {
        require(quantity > 0) { "quantity must be positive, got $quantity" }
    }

    val total: Price get() = item.price * quantity
}

sealed class OrderResult {
    data class Accepted(val order: Order, val confirmation: String) : OrderResult()
    data class Rejected(val order: Order, val reasons: List<String>) : OrderResult()
}

fun interface OrderValidator {
    fun validate(order: Order): List<String>
}

object DefaultValidator : OrderValidator {
    private const val MAX_QUANTITY = 100

    override fun validate(order: Order): List<String> = buildList {
        order.lines.forEach { line ->
            if (line.quantity > MAX_QUANTITY) {
                add("Too many '${line.item.name}': ${line.quantity} > $MAX_QUANTITY")
            }
        }
        if (order.lines.isEmpty()) {
            add("Order has no lines")
        }
    }
}

class OrderProcessor(
    private val validators: List<OrderValidator> = listOf(DefaultValidator),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun process(order: Order): OrderResult = coroutineScope {
        val reasons = validators.map { validator -> async(dispatcher) { validator.validate(order) } }
            .flatMap { it.await() }
        if (reasons.isNotEmpty()) {
            OrderResult.Rejected(order, reasons)
        } else {
            OrderResult.Accepted(order, confirmation = withContext(dispatcher) { confirm(order) })
        }
    }

    private suspend fun confirm(order: Order): String {
        delay(1.milliseconds)
        return "${order.id}-${order.total.cents.toString(16)}"
    }
}
