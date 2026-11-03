/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.jetbrains.sample.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.time.LocalDate


class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("my-app", "Today is ${LocalDate.now()}")
    }
}
