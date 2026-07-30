@file:OptIn(ExperimentalForeignApi::class)

import com.example.curl2.*
import com.example.native.custom.getGreeting
import kotlinx.cinterop.ExperimentalForeignApi

fun main() {
    curl_easy_init()
    getGreeting(1u)
}
