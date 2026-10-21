/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.url

/**
 * Represents a customizable URL that can be overridden by an environment variable.
 */
open class CustomizableUrl(
    val defaultUrl: String,
    val environmentVariable: String
) {
    val url: String
        get() = System.getenv(environmentVariable)
            ?.takeIf { it.isNotBlank() }
            ?: defaultUrl

    open val isDirectUrl: Boolean get() = url.trimEnd('/') == defaultUrl.trimEnd('/')
}