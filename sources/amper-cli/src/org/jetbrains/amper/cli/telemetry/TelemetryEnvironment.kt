/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.resources.Resource
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.context.AmperBuildLogsRoot
import org.jetbrains.amper.cli.shutdown.CliShutdownManager
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.telemetry.TelemetrySetup
import org.jetbrains.amper.util.DateTimeFormatForFilenames
import org.jetbrains.amper.util.nowInDefaultTimezone
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.name

object TelemetryEnvironment {

    private const val KOTLIN_CLI_TRACES_FILENAME = "kotlin_cli_traces.jsonl"

    /**
     * The directory name to use for traces when placed in the user-level Kotlin Toolchain cache.
     * It has to be unique, and somehow convey which project/command it came from.
     */
    private val userLevelTracesDirName: String by lazy {
        val datetime = LocalDateTime.nowInDefaultTimezone().format(DateTimeFormatForFilenames)
        val pid = ProcessHandle.current().pid() // avoid clashes with concurrent Kotlin CLI processes
        val workingDirName = Path(".").absolute().normalize().name
        "${datetime}_${pid}_${workingDirName.take(20)}"
    }

    private var movableFileOutputStream: MovableFileOutputStream? = null

    // Some standard attributes from https://opentelemetry.io/docs/specs/semconv/resource/
    private val resource: Resource = Resource.getDefault().merge(
        Resource.builder()
            .put("service.name", "KTC")
            .put("service.namespace", "org.jetbrains.amper")
            .put("service.instance.id", UUID.randomUUID().toString())
            .put("service.version", AmperBuild.mavenVersion)
            .put("os.type", System.getProperty("os.name"))
            .put("os.version", System.getProperty("os.version"))
            .put("host.arch", System.getProperty("os.arch"))
            .build()
    )

    fun setUserCacheRoot(amperUserCacheRoot: AmperUserCacheRoot) {
        moveSpansFile(newPath = userLevelTracesPath(amperUserCacheRoot))
    }

    fun setLogsRootDirectory(amperBuildLogsRoot: AmperBuildLogsRoot) {
        moveSpansFile(newPath = amperBuildLogsRoot.telemetryPath.createDirectories() / KOTLIN_CLI_TRACES_FILENAME)
    }

    private fun userLevelTracesPath(userCacheRoot: AmperUserCacheRoot): Path =
        userLevelTracesDir(userCacheRoot) / KOTLIN_CLI_TRACES_FILENAME

    /**
     * Returns the directory where telemetry traces should be placed for the current execution in global (non-project)
     * contexts. This is also the place where traces are temporarily stored very early in the CLI process lifetime
     * before we know where the project is (at which point we move everything to a project-specific location).
     */
    internal fun userLevelTracesDir(userCacheRoot: AmperUserCacheRoot): Path =
        (userCacheRoot.telemetryRoot / userLevelTracesDirName).createDirectories()

    private fun moveSpansFile(newPath: Path) {
        movableFileOutputStream?.moveTo(newPath)
            ?: error("Initial path for traces was not set. TelemetryEnvironment.setup() must be called first.")
    }

    fun setup(defaultCacheRoot: AmperUserCacheRoot) {
        val outputStream = MovableFileOutputStream(initialPath = userLevelTracesPath(defaultCacheRoot))
        movableFileOutputStream = outputStream
        val openTelemetry = TelemetrySetup.createOpenTelemetry(outputStream.buffered(), resource)
        GlobalOpenTelemetry.set(openTelemetry)
        CliShutdownManager.registerOpenTelemetryCleanup {
            openTelemetry.close()
        }
    }
}
