/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.util

import org.jetbrains.amper.processes.runProcessWithInheritedIO
import org.jetbrains.amper.system.info.OsFamily

internal suspend fun openBrowser(
    url: String,
    log: (String) -> Unit
) {
    val cmd = when {
        OsFamily.current.isWindows -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        OsFamily.current.isLinux -> listOf("xdg-open", url)
        OsFamily.current.isMac -> listOf("open", url)
        else -> return
    }

    log("Starting $cmd")

    val exitCode = runProcessWithInheritedIO(command = cmd)
    if (exitCode != 0) {
        log("$cmd failed with exit code $exitCode")
    }
}