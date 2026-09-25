/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.core

import kotlinx.serialization.Serializable

/**
 * A small domain model exercising the Kotlin features most projects use: data classes, sealed hierarchies,
 * generics, extension functions, inline functions, lambdas, and coroutines.
 */
@Serializable
data class Item(val id: Int, val name: String, val price: Price, val tags: Set<String> = emptySet())

@Serializable
@JvmInline
value class Price(val cents: Long) : Comparable<Price> {
    operator fun plus(other: Price): Price = Price(cents + other.cents)
    operator fun times(quantity: Int): Price = Price(cents * quantity)
    override fun compareTo(other: Price): Int = cents.compareTo(other.cents)
    override fun toString(): String = "%d.%02d".format(cents / 100, cents % 100)
}
