/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver

// Valid common code for this module's platform set [jvm, linuxX64]: both
// platforms are in androidx.sqlite's nonWeb target group, where
// SQLiteDriver.open(fileName) is the synchronous method.
class MyDriver : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection {
        throw UnsupportedOperationException("repro stub — never opened")
    }
}