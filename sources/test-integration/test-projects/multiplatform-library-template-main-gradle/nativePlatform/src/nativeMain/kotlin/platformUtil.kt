package org.jetbrains.kotlintoolchain.kmp.sample.platform

import platform.posix.PATH_MAX

fun getPosixPathMax(): Int {
    return PATH_MAX
}
