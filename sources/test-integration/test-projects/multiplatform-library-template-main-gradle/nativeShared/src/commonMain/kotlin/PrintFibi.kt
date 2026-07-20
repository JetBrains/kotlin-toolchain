package io.github.kotlin.fibonacci

fun printFibi(n: Int) {
    generateFibi().take(n).forEach { println(it) }
}