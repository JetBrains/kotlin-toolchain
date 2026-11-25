/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.system.info

/**
 * The exit code value conventionally returned by processes terminated with Ctrl+C (`SIGINT`) in this [OsFamily].
 */
val OsFamily.exitCodeForCtrlC: Int
    get() = when (this) {
        // Windows: STATUS_CONTROL_C_EXIT
        OsFamily.Windows -> 0xC000013A.toInt()
        // Unix convention: 128 + SIGINT (2)
        OsFamily.Linux,
        OsFamily.MacOs,
        OsFamily.FreeBSD,
        OsFamily.Solaris,
            -> 130
    }
