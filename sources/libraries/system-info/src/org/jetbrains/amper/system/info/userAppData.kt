/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.system.info

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Returns the OS-specific directory where applications store their user-specific data.
 */
fun OsFamily.getSharedUserAppDataRoot(): Path {
    val userHome = System.getProperty("user.home")
    return when (this) {
        OsFamily.Windows -> Path(getNonBlankEnv("APPDATA") ?: "$userHome\\AppData\\Roaming")
        OsFamily.MacOs -> Path("$userHome/Library/Application Support")
        OsFamily.Linux,
        OsFamily.FreeBSD,
        OsFamily.Solaris,
            -> Path(getNonBlankEnv("XDG_DATA_HOME") ?: "$userHome/.local/share")
    }
}

/**
 * Returns the value of the environment variable with the given [name], or `null` if it is not set or is blank.
 */
private fun getNonBlankEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
