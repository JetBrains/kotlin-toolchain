package io.github.kotlin.platform

import platform.posix.PATH_MAX

fun getPosixPathMax(): Int {
    return PATH_MAX
}
