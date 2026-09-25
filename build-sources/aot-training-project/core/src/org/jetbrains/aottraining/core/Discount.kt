/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.core

import kotlinx.serialization.Serializable

@Serializable
sealed interface Discount {
    fun apply(price: Price): Price

    data class Percentage(val percent: Int) : Discount {
        override fun apply(price: Price): Price = Price(price.cents - price.cents * percent / 100)
    }

    data class Fixed(val amount: Price) : Discount {
        override fun apply(price: Price): Price = if (amount > price) Price(0) else Price(price.cents - amount.cents)
    }

    data object None : Discount {
        override fun apply(price: Price): Price = price
    }
}
