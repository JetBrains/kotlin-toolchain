/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.pnpm

import io.opentelemetry.api.OpenTelemetry
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.telemetry.use
import java.nio.file.Path

class PnpmProvider(
    private val userCacheRoot: AmperUserCacheRoot,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) {
    private val tracer = openTelemetry.getTracer("org.jetbrains.amper.pnpm")

    suspend fun downloadPnpm(
        version: String,
    ): Path {
        return tracer.spanBuilder("Provision PNPM $version")
            .use { span ->
                span.setAttribute("version", version)
                val osString = when (OsFamily.current) {
                    OsFamily.Windows -> "win32"
                    OsFamily.Linux -> "linux"
                    OsFamily.MacOs -> "darwin"
                    OsFamily.FreeBSD, OsFamily.Solaris -> error("Unsupported OS family: ${OsFamily.current}")
                }

                val archString = when (Arch.current) {
                    Arch.X64 -> "x64"
                    Arch.Arm64 -> "arm64"
                }

                val extension = when (OsFamily.current) {
                    OsFamily.Windows -> "zip"
                    OsFamily.Linux, OsFamily.MacOs -> "tar.gz"
                    OsFamily.FreeBSD, OsFamily.Solaris -> error("Unsupported OS family: ${OsFamily.current}")
                }

                val archive = Downloader.downloadFileToCacheLocation(
                    url = "https://github.com/pnpm/pnpm/releases/download/v$version/pnpm-$osString-$archString.$extension",
                    userCacheRoot = userCacheRoot,
                )
                extractFileToCacheLocation(archiveFile = archive, amperUserCacheRoot = userCacheRoot)
                    .resolve(
                        "dist/pnpm.mjs"
                    )
            }
    }
}