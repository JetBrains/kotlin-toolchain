/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.jetbrains.apple.sdk.AppleSdkManagerBase
import com.jetbrains.cidr.xcode.XcodeBase
import com.jetbrains.cidr.xcode.XcodeComponentManager
import com.jetbrains.cidr.xcode.XcodeProjectId
import com.jetbrains.cidr.xcode.XcodeSettingsBase
import com.jetbrains.cidr.xcode.cache.CachedValuesManager
import com.jetbrains.cidr.xcode.cache.CachedValuesManagerImpl
import com.jetbrains.cidr.xcode.frameworks.AppleFileTypeManager
import com.jetbrains.cidr.xcode.frameworks.AppleSdkManager
import com.jetbrains.cidr.xcode.model.CoreXcodeWorkspace
import com.jetbrains.cidr.xcode.model.XcodeProjectTrackers
import com.jetbrains.cidr.xcode.xcspec.XcodeExtensionsManager
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.problems.reporting.BuildProblemImpl
import org.jetbrains.amper.problems.reporting.BuildProblemType
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.GlobalBuildProblemSource
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

internal class XcodeEnvironmentTask(
    private val processRunner: ProcessRunner,
) : Task {
    override val taskName get() = TASK_NAME

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult = context(
        processRunner,
    ) {
        val developerDirectory = selectedDeveloperDirectory()
        val actualVersion = checkXcodeVersion()
        val environment = XcodeEnvironment(
            developerDirectory = developerDirectory,
            version = actualVersion,
        )
        context(environment) {
            checkXcodeLicense()
            checkFirstLaunch()
        }

        StandaloneXcodeComponentManager.registerManager(environment.developerDirectory.absolutePathString())

        return Result(environment)
    }

    class Result(val environment: XcodeEnvironment) : TaskResult

    companion object {
        val TASK_NAME = TaskName(
            internalName = "xcodeEnvironment",
            operationMoniker = "validating Xcode environment",
        )
    }
}

private val MinimumSupportedXcodeVersion = ComparableVersion("16.0")

/**
 * Information about the active Xcode installation
 */
data class XcodeEnvironment(
    /**
     * Developer directory, usually `<Xcode.app-path>/Contents/Developer
     */
    val developerDirectory: Path,
    /**
     * Xcode version, e.g. `27.0`
     */
    val version: ComparableVersion,
) {
    context(environment: MutableMap<String, String>)
    fun configureCommandEnvironment() {
        environment["DEVELOPER_DIR"] = developerDirectory.absolutePathString()
    }
}

internal fun List<TaskResult>.xcodeEnvironment(): XcodeEnvironment =
    requireSingleDependency<XcodeEnvironmentTask.Result>().environment

context(problemReporter: ProblemReporter, processRunner: ProcessRunner)
private suspend fun selectedDeveloperDirectory(): Path {
    val result = processRunner.runProcess(
        workingDir = Path("."),
        // We don't do `xcrun xcode-select` because `xcrun` itself may require license acceptance.
        command = ["xcode-select", "--print-path"],
        outputMode = ProcessOutputMode.capture(),
    )
    val path = result.stdout.trim()
    if (result.exitCode.value != 0 || !path.endsWith("/Contents/Developer")) {
        reportXcodeEnvironmentError(
            "Xcode installation is not detected. Xcode is required to build iOS apps.\n$INSTALL_XCODE_HINT",
        )
    }
    return Path(path)
}

context(problemReporter: ProblemReporter, processRunner: ProcessRunner,)
private suspend fun checkXcodeVersion() : ComparableVersion {
    (val exitCode, val stdout) = processRunner.runProcess(
        workingDir = Path("."),
        // We don't do `xcrun xcodebuild` because `xcrun` itself may require license acceptance.
        command = ["xcodebuild", "-version"],
        outputMode = ProcessOutputMode.capture(),
    )
    val actualVersion = XCODE_VERSION_REGEX.find(stdout)
        ?.groupValues?.get(1)
        ?.let(::ComparableVersion)
    if (exitCode.value != 0 || actualVersion == null) {
        error("Unexpected xcodebuild output: $stdout")
    }
    if (actualVersion < MinimumSupportedXcodeVersion) {
        reportXcodeEnvironmentError(
            "Xcode $actualVersion is not supported. " +
                    "Select Xcode ${MinimumSupportedXcodeVersion.canonical} or newer.\n$INSTALL_XCODE_HINT",
        )
    }
    return actualVersion
}

context(problemReporter: ProblemReporter, _: ProcessRunner, _: XcodeEnvironment)
private suspend fun checkXcodeLicense() {
    if (runCommand("xcodebuild", "-license", "check").exitCode.value != 0) {
        reportXcodeEnvironmentError(
            "The Xcode license has not been accepted. Run `sudo xcodebuild -license` to review and accept it.",
        )
    }
}

context(problemReporter: ProblemReporter, _: ProcessRunner, _: XcodeEnvironment)
private suspend fun checkFirstLaunch() {
    if (runCommand("xcodebuild", "-checkFirstLaunchStatus").exitCode.value != 0) {
        reportXcodeEnvironmentError(
            "Xcode first-launch setup could not be completed. Run `sudo xcodebuild -runFirstLaunch` and try again.",
        )
    }
}

context(processRunner: ProcessRunner, xcodeEnvironment: XcodeEnvironment)
private suspend fun runCommand(vararg command: String): ProcessResult.WithOutputs {
    return processRunner.runProcess(
        workingDir = Path("."),
        command = command.asList(),
        configureEnvironment = { xcodeEnvironment.configureCommandEnvironment() },
        outputMode = ProcessOutputMode.capture(),
        input = ProcessInput.Empty,
    )
}

private const val INSTALL_XCODE_HINT = """
You can either
  - install the latest Xcode from the [App Store](https://apps.apple.com/us/app/xcode/id497799835) (recommended)
  - download Xcode from https://developer.apple.com/xcode/resources/ then select its Developer directory with `sudo xcode-select --switch <path-to-Xcode.app>`.

*Apple ID is required to download and install Xcode.*
"""

@OptIn(NonIdealDiagnostic::class)
context(problemReporter: ProblemReporter)
private fun reportXcodeEnvironmentError(message: String) {
    problemReporter.reportMessage(
        BuildProblemImpl(
            diagnosticId = XcodeEnvironmentDiagnosticId,
            source = GlobalBuildProblemSource,
            message = message,
            level = Level.Error,
            type = BuildProblemType.Generic,
        )
    )
    userReadableError("Xcode environment is not ready, see errors above.")
}

private data object XcodeEnvironmentDiagnosticId : DiagnosticId

private val XCODE_VERSION_REGEX = Regex("^Xcode (.+)$", RegexOption.MULTILINE)

private class StandaloneXcodeComponentManager(private val xcodePath: String) : XcodeComponentManager {
    override fun isUnitTestMode(): Boolean = false

    private val services = mutableMapOf<Class<*>, Any>()

    override fun <T : Any> getService(clazz: Class<T>): T {
        return services.getOrPut(clazz) {
            when {
                XcodeExtensionsManager::class.java.isAssignableFrom(clazz) -> XcodeExtensionsManager()
                XcodeProjectTrackers::class.java.isAssignableFrom(clazz) -> XcodeProjectTrackers()
                CoreXcodeWorkspace::class.java.isAssignableFrom(clazz) -> CoreXcodeWorkspace.EMPTY()
                AppleFileTypeManager::class.java.isAssignableFrom(clazz) -> AppleFileTypeManager()
                AppleSdkManagerBase::class.java.isAssignableFrom(clazz) -> AppleSdkManager()
                CachedValuesManager::class.java.isAssignableFrom(clazz) -> CachedValuesManagerImpl()
                XcodeSettingsBase::class.java.isAssignableFrom(clazz) -> XcodeSettingsBase().also { settings ->
                    settings.setSelectedXcodeBasePath(xcodePath)
                }

                XcodeBase::class.java.isAssignableFrom(clazz) -> XcodeBase()
                else -> throw IllegalArgumentException("Unknown service class is requested from XcodeComponentManager: ${clazz.name}")
            }
        }.let(clazz::cast)
    }

    override fun <T : Any> getExtensions(ep: XcodeComponentManager.EP<T>): List<T> {
        return emptyList()
    }

    companion object {
        fun registerManager(xcodePath: String) {
            XcodeComponentManager.registerImpl(object : XcodeComponentManager.Initializer {
                private val appMan = StandaloneXcodeComponentManager(xcodePath)
                private val proMan = StandaloneXcodeComponentManager(xcodePath)

                override val applicationManager: XcodeComponentManager
                    get() = appMan

                override fun getProjectManager(projectId: XcodeProjectId): XcodeComponentManager {
                    return proMan
                }
            })
        }
    }
}
