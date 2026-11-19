/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
@file:OptIn(ExperimentalForeignApi::class)

package org.jetbrains.kotlintoolchain.kmp.sample.consumer

import com.example.native.custom.getCustomGreeting
import com.example.native.custom.getCustomGreeting2
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.jetbrains.kotlintoolchain.kmp.sample.curl.CustomGreeting
import org.jetbrains.kotlintoolchain.kmp.sample.curl.fetchAndPrintUrl

fun foo() {
    fetchAndPrintUrl("https://example.com")
    println(CustomGreeting)
    // The symbols below come from the commonized cinterop part of the published 'libraryNested' library,
    // which is exported by the published 'libraryCinterop' library. Two different cinterop klibs contribute
    // to the same package here, so both of them have to be on the metadata compilation classpath.
    println(getCustomGreeting()?.toKString())
    println(getCustomGreeting2()?.toKString())
}
