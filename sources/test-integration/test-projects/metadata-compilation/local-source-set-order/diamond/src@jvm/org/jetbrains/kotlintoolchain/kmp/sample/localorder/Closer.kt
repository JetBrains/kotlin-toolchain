/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.localorder

// 'jvm' is the only platform of this module that is not covered by the 'nonJvm' alias.
actual interface Closer {
    actual val isClosed: Boolean
}
