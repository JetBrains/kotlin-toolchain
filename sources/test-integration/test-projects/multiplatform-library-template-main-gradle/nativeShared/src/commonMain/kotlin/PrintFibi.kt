package org.jetbrains.kotlintoolchain.kmp.test.sample.fibonacci

fun printFibi(n: Int) {
    generateFibi().take(n).forEach { println(it) }
}