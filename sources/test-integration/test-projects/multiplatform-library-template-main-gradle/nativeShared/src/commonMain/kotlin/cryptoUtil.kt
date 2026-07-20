package io.github.kotlin.ctypto

import org.kotlincrypto.random.RandomnessProcurementException

fun throwRandomnessProcurementException(): Int {
    throw RandomnessProcurementException("It is there, and it is thrown")
}