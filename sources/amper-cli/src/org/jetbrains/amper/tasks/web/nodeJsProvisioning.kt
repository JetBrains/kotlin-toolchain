/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.web

import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Downloads the Node.js distribution for the current platform and returns the path to its `node` executable.
 */
internal suspend fun downloadNodeJs(userCacheRoot: AmperUserCacheRoot): Path {
    val osString = when (OsFamily.current) {
        OsFamily.Windows -> "win"
        OsFamily.Linux -> "linux"
        OsFamily.MacOs -> "darwin"
        OsFamily.FreeBSD, OsFamily.Solaris -> error("Unsupported OS family: ${OsFamily.current}")
    }

    val archString = when (Arch.current) {
        Arch.X64 -> "x64"
        Arch.Arm64 -> "arm64"
    }

    val extension = if (OsFamily.current.isWindows) "zip" else "tar.gz"

    val distributionName = "node-v$NODE_JS_VERSION-$osString-$archString"
    val archive = Downloader.downloadFileToCacheLocation(
        url = "https://nodejs.org/dist/v$NODE_JS_VERSION/$distributionName.$extension",
        userCacheRoot = userCacheRoot,
    )
    val distribution = extractFileToCacheLocation(
        archiveFile = archive,
        amperUserCacheRoot = userCacheRoot,
        ExtractOptions.STRIP_ROOT,
    )

    return if (OsFamily.current.isWindows) {
        distribution / "node.exe"
    } else {
        distribution / "bin" / "node"
    }
}

private const val NODE_JS_VERSION = "26.5.1"
