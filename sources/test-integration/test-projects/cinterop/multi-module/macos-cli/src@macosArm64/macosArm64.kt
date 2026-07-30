@file:OptIn(ExperimentalForeignApi::class)

import com.example.curl2.*
import kotlinx.cinterop.ExperimentalForeignApi

fun macosArm64() {
    curl_easy_init()
}