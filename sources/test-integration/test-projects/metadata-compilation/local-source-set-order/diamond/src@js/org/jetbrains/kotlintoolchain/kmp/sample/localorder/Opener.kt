/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.localorder

// 'js' is the only platform of this module that is not covered by the 'nonWeb' alias.
actual interface Opener {
    actual val hasConnectionPool: Boolean
}
