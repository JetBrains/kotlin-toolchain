/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.localorder

/**
 * The common declaration has no 'open' member at all, it is only added by the 'nonWeb' refinement.
 * A consumer overriding 'open' thus only compiles if the metadata klib of 'nonWeb' precedes the one of 'common'.
 */
expect interface Driver {
    val hasConnectionPool: Boolean
}
