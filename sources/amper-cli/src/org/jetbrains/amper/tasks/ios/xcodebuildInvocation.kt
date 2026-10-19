/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingNames
import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.native.swiftpm.xcodebuildPlatform
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.pathString

/**
 * Directories used by an `xcodebuild` invocation for a KTC iOS app module.
 */
internal class XcodebuildDirs(workingDir: Path) {
    val derivedDataPath: Path = workingDir / "derivedData"
    val objRootPath: Path = workingDir / "tmp"
    val symRootPath: Path = workingDir / "bin"
}

/**
 * The common `xcodebuild` arguments needed to build the app scheme of the given iOS app [module]
 * (shared by `build` and `archive` actions).
 * Does not include the action itself.
 *
 * Note: `OBJROOT`/`SYMROOT` must not be overridden for the `archive` action, because `xcodebuild archive` relies on
 * its own `ArchiveIntermediates/<scheme>/BuildProductsPath` layout and fails if these settings are overridden.
 * Use [xcodebuildBuildRootArgs] for the `build` action only.
 */
internal fun xcodebuildCommonAppArgs(
    module: AmperModule,
    platform: Platform,
    buildType: BuildType,
    dirs: XcodebuildDirs,
): List<String> = [
    XCRUN_EXECUTABLE,
    "xcodebuild",
    "-project", module.xcodeProjectPath.absolutePathString(),
    "-scheme", IosConventions.SCHEME_NAME,
    "-destination", "generic/platform=${platform.xcodebuildPlatform.destination}",
    "-configuration", buildType.name,
    "-derivedDataPath", dirs.derivedDataPath.pathString,
    "KOTLIN_CLI_WRAPPER_PATH=${ProjectCliContext.wrapperScriptPath.absolutePathString()}",
]

/**
 * Overrides the build roots for the `build` action, so that the products end up in [XcodebuildDirs.symRootPath].
 * Must not be used with the `archive` action.
 */
internal fun xcodebuildBuildRootArgs(dirs: XcodebuildDirs): List<String> = [
    "${BuildSettingNames.OBJROOT}=${dirs.objRootPath.pathString}",
    "${BuildSettingNames.SYMROOT}=${dirs.symRootPath.pathString}",
]

/**
 * Runs the given `xcodebuild` [command] with KTC's interactive log parsing,
 * and with the environment needed for the KTC build phase in the Xcode project.
 *
 * @return the exit code of the process
 */
context(executionContext: TaskGraphExecutionContext, xcodeEnvironment: XcodeEnvironment)
internal suspend fun runXcodebuild(
    module: AmperModule,
    command: List<String>,
    workingDir: Path,
    prebuildResult: IosPreBuildTask.Result,
    userCacheRoot: AmperUserCacheRoot,
    terminal: Terminal,
    processRunner: ProcessRunner,
): Int {
    val result = runXcodebuildWithLogParsing(
        userCacheRoot = userCacheRoot,
        terminal = terminal,
        sink = executionContext.eventSink,
    ) { pipe ->
        spanBuilder("xcodebuild")
            .setAmperModule(module)
            .setListAttribute("args", command)
            .use { span ->
                processRunner.runProcess(
                    workingDir = workingDir,
                    command = command,
                    span = span,
                    configureEnvironment = {
                        xcodeEnvironment.configureCommandEnvironment()
                        put(IosPreBuildTask.Result.ENV_JSON_NAME, Json.encodeToString(prebuildResult))
                    },
                    outputMode = pipe,
                )
            }
    }
    return result.exitCode.value
}
