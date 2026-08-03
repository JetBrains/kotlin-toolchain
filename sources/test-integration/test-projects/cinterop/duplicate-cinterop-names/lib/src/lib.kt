/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
@file:OptIn(ExperimentalForeignApi::class)

package com.example.lib

import com.example.lib.custom.getLibGreeting
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

fun libGreeting(): String = getLibGreeting()?.toKString() ?: "?"
