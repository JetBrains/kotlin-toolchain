package io.github.kotlin.crypto.cinterop

import kotlinx.cinterop.ExperimentalForeignApi
import org.kotlincrypto.random.internal.__getrandom

@OptIn(ExperimentalForeignApi::class)
fun getLinuxRandom(): Long {
    return __getrandom(
        __buf = null,
        __len = 100L.toULong(),
        __is_nonblock = 0,
    )
}