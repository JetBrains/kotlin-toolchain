/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.web

import io.opentelemetry.api.trace.Span
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.pnpm.PnpmDist
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.pathString

internal const val PNPM_VERSION = "11.9.0"

internal suspend fun ProcessRunner.disablePnpmUpdateNotifier(
    workingDir: Path,
    pnpm: PnpmDist,
    logger: Logger,
    span: Span,
) {
    val result = runProcess(
        workingDir = workingDir,
        command = [
            pnpm.executable.pathString,
            "config",
            "set",
            "--location=project",
            "updateNotifier",
            "false"
        ],
        span = span,
        outputMode = ProcessOutputMode.listenAndCaptureStderr(
            listener = LoggingProcessOutputListener(logger),
        ),
    )

    if (result.exitCode != 0) {
        error(
            "pnpm configuration exits with the code ${result.exitCode}"
        )
    }
}