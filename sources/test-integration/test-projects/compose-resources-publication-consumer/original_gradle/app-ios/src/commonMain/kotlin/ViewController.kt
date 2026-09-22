/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import com.example.libnative.greeting

@Composable
fun App() {
    BasicText(greeting())
}

fun ViewController() = ComposeUIViewController { App() }
