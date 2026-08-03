/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
@file:OptIn(ExperimentalForeignApi::class)

package com.example.app

// Both cinterops are named 'custom', but they come from different modules, so both must be visible here.
import com.example.app.custom.getAppGreeting
import com.example.lib.custom.getLibGreeting
import com.example.lib.libGreeting
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

fun greetings(): List<String> = listOf(
    getAppGreeting()?.toKString() ?: "?",
    getLibGreeting()?.toKString() ?: "?",
    libGreeting(),
)
