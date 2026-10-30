/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.userid

import java.lang.System

/**
 * `true` if the current process is detected to be from an internal JetBrains user.
 */
internal val isInternalUserForFUS: Boolean by lazy { detectInternalUserForFUS() }

private fun detectInternalUserForFUS(): Boolean {
    if (System.getenv("KOTLIN_TOOLCHAIN_INTERNAL_USER")?.toBooleanStrictOrNull() == true) {
        return true
    }
    if (System.getenv("KOTLIN_FROM_SOURCES")?.toBooleanStrictOrNull() == true) {
        return true
    }
    // we also detect internal JetBrains users that don't set up their CI with special env vars
    if (System.getenv("TEAMCITY_VERSION") != null) {
        val buildUrl = System.getenv("BUILD_URL")
        if (buildUrl != null && buildUrl.startsWith("https://buildserver.labs.intellij.net")) {
            return true // internal CI
        }
    }
    return false
}
