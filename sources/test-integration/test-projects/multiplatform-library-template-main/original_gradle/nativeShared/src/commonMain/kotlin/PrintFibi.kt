package org.jetbrains.kotlintoolchain.kmp.sample.fibonacci

fun printFibi(n: Int) {
    generateFibi().take(n).forEach { println(it) }
}