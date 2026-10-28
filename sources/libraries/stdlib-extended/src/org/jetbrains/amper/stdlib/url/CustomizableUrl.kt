/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.url

import kotlin.text.trimEnd

/**
 * Represents a customizable URL that can be overridden by an environment variable.
 */
open class CustomizableUrl(
    defaultUrl: String,
    val environmentVariable: String,
    val deprecatedEnvVariables: List<String> = []
) {
    val defaultUrl = defaultUrl.trimEnd('/')

    val url: String
        get() = getUrlFromEnvVariable(environmentVariable)
            ?: getUrlFromEnvVariables(deprecatedEnvVariables)
            ?: defaultUrl

    open val isDefaultUrl: Boolean get() = url == defaultUrl

    companion object {
        private fun getUrlFromEnvVariable(environmentVariable: String): String? {
            return System.getenv(environmentVariable)
                ?.takeIf { it.isNotBlank() }
                ?.trimEnd('/')
        }

        private fun getUrlFromEnvVariables(environmentVariables: List<String>): String? {
            return environmentVariables.firstNotNullOfOrNull { getUrlFromEnvVariable(it) }
        }
    }
}