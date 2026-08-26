/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import org.jetbrains.annotations.Nls

sealed interface AndroidSdkResult {
    data class Success(val androidPackage: AndroidSdkPackage) : AndroidSdkResult
    data class Error(val message: @Nls String) : AndroidSdkResult
}