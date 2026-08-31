/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.events.operationEventScope
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.tasks.MobileRunSettings
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.native.swiftpm.XcodebuildPlatform
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

class IosPrepareDeviceForRunTask(
    override val taskName: TaskName,
    private val platform: Platform,
    private val runSettings: MobileRunSettings,
    private val buildType: BuildType,
    private val processRunner: ProcessRunner,
    private val buildSettingsResolution: XcodeBuildSettingsResolution,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult = context(executionContext.eventSink) {
        val builtApp = dependenciesResult.requireSingleDependency<IosBuildTask.Result>()

        if (!platform.isIosSimulator && !checkAppIsSigned(builtApp.appPath)) {
            userReadableError(
                "Running an unsigned app on a physical device (${platform.pretty}) is not possible. " +
                        "Please select a development team in the Xcode project editor (Signing & Capabilities) " +
                        "or use a simulator platform instead."
            )
        }

        val destinations = dependenciesResult
            .requireSingleDependency<PrepareIOSPlatformTask.Result>()
            .destinations
        val bundleId = buildSettingsResolution.getResolver(buildType, dependenciesResult)
            .productBundleIdentifier

        val device = if (platform.isIosSimulator) {
            prepareSimulator(
                userProvidedDeviceId = runSettings.deviceId?.let(::XcodeDeviceId),
                processRunner = processRunner,
                destinations = destinations,
                platform = platform,
            ).also ensureBooted@ { (id, name) ->
                if (processRunner.isSimulatorBooted(id)) {
                    return@ensureBooted
                }

                operationEventScope("Waiting for the iOS Simulator `$name` to boot") {
                    processRunner.bootAndWaitSimulator(
                        deviceId = id,
                        // Maybe move this to the `run` task as a separate operation?
                        forceShowWindow = true,
                    )
                }
            }
        } else {
            preparePhysicalDevice(destinations)
        }

        operationEventScope("Installing the app on '${device.name}`") {
            if (platform.isIosSimulator) {
                processRunner.installAppOnDevice(device.id, builtApp.appPath)
            } else {
                processRunner.installAppOnPhysicalDevice(device.id, builtApp.appPath)
            }
        }

        return Result(
            readyDeviceId = device.id,
            bundleId = bundleId,
        )
    }

    private fun preparePhysicalDevice(destinations: List<XcodeDestination>): XcodeDestination {
        val matchingDestinations = destinations.filter { it.platform == XcodebuildPlatform.iOS }

        runSettings.deviceId?.let { userProvidedDeviceId ->
            val userProvidedDeviceId = XcodeDeviceId(userProvidedDeviceId)
            val destination = matchingDestinations.find { destination -> destination.id == userProvidedDeviceId }
            if (destination == null) {
                userReadableError {
                    appendLine("No physical device with the ID `$userProvidedDeviceId` is found among " +
                            "known/supported destinations for this project.")
                    appendAvailableDestinations(matchingDestinations)
                }
            }
            return destination
        }

        // Pick any physical device
        return matchingDestinations.firstOrNull() ?: userReadableError {
            appendLine("No physical device is found among " +
                    "known/supported destinations for this project.")
            appendAvailableDestinations(matchingDestinations)
        }
    }

    private suspend fun checkAppIsSigned(appPath: Path): Boolean = processRunner.runProcess(
        workingDir = Path("."),
        command = ["codesign", "-v", appPath.absolutePathString()],
        outputMode = ProcessOutputMode.Discard,
    ).exitCode == 0

    class Result(
        val readyDeviceId: XcodeDeviceId,
        val bundleId: String,
    ) : TaskResult
}

context(_: OperationEventSink)
suspend fun prepareSimulator(
    processRunner: ProcessRunner,
    userProvidedDeviceId: XcodeDeviceId?,
    destinations: List<XcodeDestination>,
    platform: Platform,
): XcodeDestination {
    val matchingDestinations = destinations.filter { it.platform == XcodebuildPlatform.`iOS Simulator` }

    if (userProvidedDeviceId != null) {
        val matchingDestination = matchingDestinations.find { it.id == userProvidedDeviceId }
        if (matchingDestination == null) {
            userReadableError {
                appendLine("Simulator with the ID `$userProvidedDeviceId` is not a " +
                        "known/supported destination for this project.")
                appendAvailableDestinations(matchingDestinations)
                appendLine("NOTE: Simulators may be created automatically, when no device ID is provided")
            }
        }
        return matchingDestination
    }

    val destinationsById = matchingDestinations.associateBy { it.id }
    processRunner.selectBestIosSimulator(
        // Only select from the matching destinations from the project
        preFilter = { id -> id in destinationsById },
    )?.let {
        return destinationsById.getValue(it)
    }

    return operationEventScope("Creating iOS Simulator") {
        processRunner.provisionLatestIPhoneSimulator(platform)
    }
}

private fun StringBuilder.appendAvailableDestinations(destinations: List<XcodeDestination>) {
    if (destinations.isEmpty()) {
        appendLine("No ready destinations is available for the module.")
    } else {
        appendLine("Available destinations:")
        destinations.forEach {
            append("  - ").append(it.name)
            if (it.platform != null) {
                append(" on ").append(it.platform.destination)
            }
            if (it.os != null) {
                append(" (${it.os.canonical})")
            }
            append(" | ID: ").append(it.id)
            appendLine()
        }
        // TODO: Add more info about unsupported destinations/non-destinations?
    }
}