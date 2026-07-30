/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.muted
import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingNames
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.PrintToTerminalProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.pipe.ProcessPipe
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isExecutable
import kotlin.io.path.pathString
import kotlin.io.path.setPosixFilePermissions

class IosBuildTask(
    override val platform: Platform,
    override val module: AmperModule,
    override val buildType: BuildType,
    private val taskOutputPath: TaskOutputRoot,
    override val taskName: TaskName,
    private val userCacheRoot: AmperUserCacheRoot,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
    private val buildSettingsResolution: XcodeBuildSettingsResolution,
) : BuildTask {
    init {
        require(platform.isDescendantOf(Platform.IOS)) { "Invalid iOS platform: $platform" }
    }

    override val isTest: Boolean
        get() = false

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val prebuildResult = dependenciesResult.requireSingleDependency<IosPreBuildTask.Result>()
        val settings = buildSettingsResolution.getResolver(buildType, dependenciesResult)

        val workingDir = taskOutputPath.path.createDirectories()
        val derivedDataPath = workingDir / "derivedData"
        val objRootPath = workingDir / "tmp"
        val symRootPath = workingDir / "bin"

        val xcodebuildArgs = buildList {
            this += "xcrun"
            this += "xcodebuild"
            this += "-project"; this += module.xcodeProjectPath.absolutePathString()
            this += "-scheme"; this += IosConventions.SCHEME_NAME
            this += "-destination"; this += "generic/platform=${platform.toXcodePlatformTitle()}"
            this += "-configuration"; this += buildType.name
            this += "-derivedDataPath"; this += derivedDataPath.pathString
            this += "${BuildSettingNames.OBJROOT}=${objRootPath.pathString}"
            this += "${BuildSettingNames.SYMROOT}=${symRootPath.pathString}"
            this += "KOTLIN_CLI_WRAPPER_PATH=${ProjectCliContext.wrapperScriptPath.absolutePathString()}"
            if (platform.isIosSimulator) {
                // Constrain built architectures to avoid universal simulator build
                this +="${BuildSettingNames.ARCHS}=${platform.architecture}"
            }
            val hasTeamId = !settings.developmentTeam.isNullOrBlank()
            val isSigningDisabled = settings.codeSigningAllowed == "NO"
            if (!platform.isIosSimulator && !hasTeamId && !isSigningDisabled) {
                logger.warn("`DEVELOPMENT_TEAM` build setting is not detected in the Xcode project. " +
                        "Adding `CODE_SIGNING_ALLOWED=NO` to disable signing. " +
                        "You can still sign the app manually later.")
                this += "CODE_SIGNING_ALLOWED=NO"
            }
            this += "build"
        }

        coroutineScope {
            val executable = prepareLogParsingUtility(userCacheRoot)
            val pipe = ProcessPipe(
                includeStderr = true,
                eavesDroppingListener = LoggingProcessOutputListener(
                    logger = logger,
                    prefix = "xcodebuild/out",
                    stdErrPrefix = "xcodebuild/err",
                    stdoutLoggingLevel = Level.DEBUG,
                    stderrLoggingLevel = Level.DEBUG,
                ),
            )

            terminal.muted("Running xcodebuild with xcbeautify $XCBEAUTIFY_VERSION")

            // Need to launch log parser in parallel
            val parserProcessJob = launch {
                runProcess(
                    workingDir = workingDir,
                    command = listOf(
                        executable.pathString,
                        "--disable-logging", // disable big version banner - we do it ourselves
                        "--quiet",
                    ),
                    outputMode = ProcessOutputMode.listen(PrintToTerminalProcessOutputListener(terminal)),
                    input = pipe,
                )
            }

            spanBuilder("xcodebuild")
                .setAmperModule(module)
                .setListAttribute("args", xcodebuildArgs)
                .use { span ->
                    val result = processRunner.runProcess(
                        workingDir = workingDir,
                        command = xcodebuildArgs,
                        span = span,
                        environment = mapOf(
                            IosPreBuildTask.Result.ENV_JSON_NAME to Json.encodeToString(prebuildResult),
                        ),
                        outputMode = pipe,
                    )

                    // Ensure the parser is done to avoid putting the final log entries into the middle of the log
                    parserProcessJob.join()

                    if (result.exitCode != 0) {
                        userReadableError("xcodebuild invocation failed, check the log above.")
                    }
                }
        }

        return Result(
            appPath = symRootPath / "${buildType.name}-${platform.sdk}" / "${settings.productName}.app",
        )
    }

    class Result(
        val appPath: Path,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        suspend fun prepareLogParsingUtility(userCacheRoot: AmperUserCacheRoot): Path {
            val archString = when(Arch.current) {
                Arch.X64 -> "x86_64"
                Arch.Arm64 -> "arm64"
            }
            val version = XCBEAUTIFY_VERSION
            val archive = Downloader.downloadFileToCacheLocation(
                url = "https://github.com/cpisciotta/xcbeautify/releases/download/$version/xcbeautify-$version-$archString-apple-macosx.zip",
                userCacheRoot = userCacheRoot,
            )
            val executable = extractFileToCacheLocation(archiveFile = archive, amperUserCacheRoot = userCacheRoot)
                .resolve("xcbeautify")
            if (!executable.isExecutable()) {
                val permissions = executable.getPosixFilePermissions()
                @Suppress("RETURN_VALUE_NOT_USED") // KT-86696
                executable.setPosixFilePermissions(permissions + PosixFilePermission.OWNER_EXECUTE)
            }
            return executable
        }
    }
}

const val XCBEAUTIFY_VERSION = "3.2.1"