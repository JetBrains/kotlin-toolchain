/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
@file:OptIn(ExperimentalForeignApi::class)
package org.jetbrains.kotlintoolchain.kmp.sample.curl

import kotlinx.cinterop.*
import libcurl.*
import com.example.native.custom.*

fun fetchAndPrintUrl(url: String) {
    val curl = curl_easy_init()
    if (curl != null) {
        curl_easy_setopt(curl, CURLOPT_URL, url)
        curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L)
        val res = curl_easy_perform(curl)
        if (res != CURLE_OK) {
            println("curl_easy_perform() failed ${curl_easy_strerror(res)?.toKString()}")
        }
        curl_easy_cleanup(curl)
    }
}

val CustomGreeting: String
    get() = getCustomGreeting().toString()