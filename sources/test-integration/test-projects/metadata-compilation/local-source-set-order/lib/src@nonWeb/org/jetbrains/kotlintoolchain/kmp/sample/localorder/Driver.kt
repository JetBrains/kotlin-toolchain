/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.localorder

actual interface Driver {
    actual val hasConnectionPool: Boolean
    fun open(fileName: String): String
}
