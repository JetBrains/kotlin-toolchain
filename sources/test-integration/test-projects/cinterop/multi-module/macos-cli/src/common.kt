@file:OptIn(ExperimentalForeignApi::class)

import com.example.curl2.*
import kotlinx.cinterop.ExperimentalForeignApi

fun common() {
    curl_easy_init()
}