/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.nodejs

import io.opentelemetry.api.OpenTelemetry
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.telemetry.use
import kotlin.io.path.div

/**
 * Downloads the Node.js distribution for the current platform and returns the path to its `node` executable.
 */
class NodeJsProvider(
    private val userCacheRoot: AmperUserCacheRoot,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) {
    private val tracer = openTelemetry.getTracer("org.jetbrains.amper.nodejs")

    suspend fun downloadNodeJs(
        version: String,
    ): NodeJsDist {
        return tracer.spanBuilder("Provision Node.JS $version")
            .use { span ->
                span.setAttribute("version", version)
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

                val distributionName = "node-v$version-$osString-$archString"
                val archive = Downloader.downloadFileToCacheLocation(
                    url = "https://nodejs.org/dist/v$version/$distributionName.$extension",
                    userCacheRoot = userCacheRoot,
                )
                val distribution = extractFileToCacheLocation(
                    archiveFile = archive,
                    amperUserCacheRoot = userCacheRoot,
                    ExtractOptions.STRIP_ROOT,
                )

                NodeJsDist(
                    distribution,
                    if (OsFamily.current.isWindows) {
                        distribution / "node.exe"
                    } else {
                        distribution / "bin" / "node"
                    }
                )
            }
    }
}
