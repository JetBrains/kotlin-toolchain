/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlintoolchain.kmp.sample.consumer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// The 'noSources' library has no code of its own, it only re-exports kotlinx-coroutines-core.
val scopeFromExportedDependency: CoroutineScope = CoroutineScope(Dispatchers.Default)
