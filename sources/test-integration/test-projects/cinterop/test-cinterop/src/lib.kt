/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import com.example.native.getCinteropAnswer

fun getAnswerFromMainSources(): Int = getCinteropAnswer()