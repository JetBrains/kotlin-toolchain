/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.service

import kotlinx.coroutines.runBlocking
import org.jetbrains.aottraining.core.Catalog
import org.jetbrains.aottraining.core.Item
import org.jetbrains.aottraining.core.Order
import org.jetbrains.aottraining.core.OrderLine
import org.jetbrains.aottraining.core.OrderProcessor
import org.jetbrains.aottraining.core.OrderResult
import org.jetbrains.aottraining.core.Price
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Service

@SpringBootApplication
class ServiceApplication {
    @Bean
    fun catalog(): Catalog = Catalog(
        listOf(
            Item(1, "Pen", Price(150), setOf("office")),
            Item(2, "Notebook", Price(499), setOf("office", "paper")),
        )
    )

    @Bean
    fun demo(orders: OrderService): CommandLineRunner = CommandLineRunner {
        println(orders.placeOrder(mapOf(1 to 2, 2 to 1)))
    }
}

@Service
class OrderService(private val catalog: Catalog) {
    private val processor = OrderProcessor()

    fun placeOrder(quantitiesByItemId: Map<Int, Int>): String {
        val lines = quantitiesByItemId.map { (id, quantity) ->
            OrderLine(item = requireNotNull(catalog[id]) { "Unknown item $id" }, quantity = quantity)
        }
        val result = runBlocking { processor.process(Order(id = "cli", lines = lines)) }
        return when (result) {
            is OrderResult.Accepted -> "Accepted: ${result.confirmation} (total ${result.order.total})"
            is OrderResult.Rejected -> "Rejected: ${result.reasons.joinToString()}"
        }
    }
}

fun main(args: Array<String>) {
    runApplication<ServiceApplication>(*args)
}
