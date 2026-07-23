/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.test.sample.consumer

import org.jetbrains.kotlintoolchain.kmp.test.sample.fibonacci.generateFibi

fun foo() {
    println("Fibonacci result (Ios) = ${generateFibi()}")
}