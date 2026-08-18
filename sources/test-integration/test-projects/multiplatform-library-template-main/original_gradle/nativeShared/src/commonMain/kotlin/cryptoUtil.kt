package org.jetbrains.kotlintoolchain.kmp.sample.ctypto

import org.kotlincrypto.random.RandomnessProcurementException

fun throwRandomnessProcurementException(): Int {
    throw RandomnessProcurementException("It is there, and it is thrown")
}