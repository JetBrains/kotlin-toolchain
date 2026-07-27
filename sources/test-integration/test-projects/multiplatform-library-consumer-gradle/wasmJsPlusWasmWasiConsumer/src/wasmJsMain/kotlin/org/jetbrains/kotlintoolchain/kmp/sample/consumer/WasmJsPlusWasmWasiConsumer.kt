/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.consumer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlintoolchain.kmp.sample.wasmJsElement

fun main(args: Array<String>) {
    println(wasmJsElement)
}

suspend fun foo() {
    // kotlinx coroutine classes come from the exported compilation dependency of the wasmJsPlusWasmWasi library
    coroutineScope {
        launch {
            println(wasmJsElement)
        }
    }
}