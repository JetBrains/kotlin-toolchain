/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.tasks.native.swiftpm.XcodebuildPlatform
import org.jetbrains.amper.tasks.native.swiftpm.clangArch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.milliseconds

const val XCRUN_EXECUTABLE = "/usr/bin/xcrun"

suspend fun ProcessRunner.installAppOnDevice(deviceId: XcodeDeviceId, appPath: Path) =
    SimCtl.install(deviceId = deviceId.value, appPath = appPath)

suspend fun ProcessRunner.installAppOnPhysicalDevice(deviceId: XcodeDeviceId, appPath: Path) =
    DeviceCtl.installApp(deviceId = deviceId.value, appPath = appPath)

suspend fun ProcessRunner.launchAppOnDevice(deviceId: XcodeDeviceId, bundleId: String) =
    SimCtl.launch(deviceId = deviceId.value, bundleId = bundleId)

suspend fun ProcessRunner.launchAppOnPhysicalDevice(deviceId: XcodeDeviceId, bundleId: String) =
    DeviceCtl.launchProcess(deviceId = deviceId.value, bundleId = bundleId)

suspend fun ProcessRunner.shutdownDevice(deviceId: XcodeDeviceId) =
    SimCtl.shutdown(deviceId = deviceId.value)

suspend fun ProcessRunner.selectBestIosSimulator(
    preFilter: (XcodeDeviceId) -> Boolean = { true },
): XcodeDeviceId? {
    class DeviceCandidateImpl(
        override val deviceTypeIdentifier: String,
        override val runtimeVersion: ComparableVersion,
        val id: XcodeDeviceId,
    ) : DeviceCandidate

    val candidates = buildList {
        for ([runtimeId, devices] in SimCtl.queryDevices(SimCtl.DeviceFilter.Available).runtimeIdToDevices) {
            if (!runtimeId.startsWith(IOS_SIMULATOR_RUNTIME_ID_PREFIX)) continue

            // e.g. com.apple.CoreSimulator.SimRuntime.iOS-26-5
            val runtimeVersion = runtimeId.removePrefix(IOS_SIMULATOR_RUNTIME_ID_PREFIX).replace('-', '.')
            for ((deviceId, deviceTypeIdentifier) in devices) {
                val deviceId = XcodeDeviceId(deviceId)
                if (!preFilter(deviceId)) continue
                this += DeviceCandidateImpl(
                    id = deviceId,
                    deviceTypeIdentifier = deviceTypeIdentifier,
                    runtimeVersion = ComparableVersion(runtimeVersion),
                )
            }
        }
    }
    return selectBestIosDevice(candidates)?.id
}

suspend fun ProcessRunner.isSimulatorBooted(
    deviceId: XcodeDeviceId,
): Boolean {
    val device = SimCtl.queryDevices(SimCtl.DeviceFilter.DeviceId(deviceId))
        .runtimeIdToDevices.entries.flatMap { [_, devices] -> devices }.singleOrNull()
        ?: userReadableError("Simulator device $deviceId disappeared unexpectedly")
    return device.state.lowercase() == "booted"
}

// We assume that the device is not yet booted
suspend fun ProcessRunner.bootAndWaitSimulator(
    deviceId: XcodeDeviceId,
    forceShowWindow: Boolean = false,
) {
    if (forceShowWindow) {
        // The `open` command works without any errors/warnings regardless of the simulator boot status.
        // It boots the simulator on demand and brings its window forward.
        runProcess(
            workingDir = Path("."),
            command = [
                "open", "-a", "Simulator",
                // We specify the simulator ID to open a window for
                "--args", "-CurrentDeviceUDID",
                deviceId.value,
            ],
            outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger)),
        )
    }

    SimCtl.boot(deviceId.value)
    repeat(20) {
        if (isSimulatorBooted(deviceId)) {
            return  // Success
        }
        delay(500.milliseconds)
    }
    userReadableError("Simulator boot timeout for `${deviceId}`.")
}

suspend fun ProcessRunner.provisionLatestIPhoneSimulator(
    platform: Platform,
): XcodeDestination {
    // Try to choose a base iPhone of the latest model of the latest runtime

    val architecture = platform.clangArch

    class DeviceTypeCandidate(
        override val deviceTypeIdentifier: String,
        override val runtimeVersion: ComparableVersion,
        val runtimeIdentifier: String,
        val name: String,
    ) : DeviceCandidate

    val candidates = buildList {
        for (runtime in SimCtl.listAvailableRuntimes().runtimes) {
            if (
                !runtime.identifier.startsWith(IOS_SIMULATOR_RUNTIME_ID_PREFIX)
                || architecture !in runtime.supportedArchitectures
            ) continue

            for ((name, identifier, productFamily) in runtime.supportedDeviceTypes) {
                if (productFamily != IPHONE_PRODUCT_FAMILY) continue
                this += DeviceTypeCandidate(
                    deviceTypeIdentifier = identifier,
                    runtimeVersion = ComparableVersion(runtime.version),
                    runtimeIdentifier = runtime.identifier,
                    name = name,
                )
            }
        }
    }
    val (name, runtimeIdentifier, deviceTypeIdentifier, runtimeVersion) = selectBestIosDevice(candidates)
        ?: userReadableError("Failed to find a runtime that supports the `iPhone` product family")

    val newDeviceId = SimCtl.create(
        name = name,
        deviceTypeId = deviceTypeIdentifier,
        runtimeId = runtimeIdentifier,
    )

    return XcodeDestination(
        id = XcodeDeviceId(newDeviceId),
        platform = XcodebuildPlatform.`iOS Simulator`,
        name = name,
        arch = architecture,
        os = runtimeVersion,
        error = null,
    )
}

private interface DeviceCandidate {
    val deviceTypeIdentifier: String
    val runtimeVersion: ComparableVersion
}

private fun <T : DeviceCandidate> selectBestIosDevice(
    candidates: List<T>,
): T? = candidates.maxWithOrNull(
    compareBy(
        DeviceCandidate::runtimeVersion,
        { "iPhone" in it.deviceTypeIdentifier },
        { IPhoneModelNumberRegex.find(it.deviceTypeIdentifier)?.groupValues[1]?.toIntOrNull() ?: 0 },
        { "Pro" !in it.deviceTypeIdentifier },
        { "Max" !in it.deviceTypeIdentifier },
        { "Plus" !in it.deviceTypeIdentifier },
        { "Air" !in it.deviceTypeIdentifier },
    )
)

private abstract class Xcrun {
    context(_: ProcessRunner)
    protected suspend fun xcrun(vararg args: String): ProcessResult =
        xcrun(*args, outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger)))

    context(processRunner: ProcessRunner)
    protected suspend fun <R : ProcessResult> xcrun(
        vararg args: String,
        outputMode: ProcessOutputMode<R>,
    ): R = processRunner.runProcess(
        workingDir = Path("."),
        command = listOf(XCRUN_EXECUTABLE) + args,
        outputMode = outputMode,
    ).also {
        if (it.exitCode != 0) {
            userReadableError("xcrun `${args.contentToString()}` failed with exit code ${it.exitCode}")
        }
    }
}

private object SimCtl : Xcrun() {
    private val SimCtlOutputFormat = Json {
        ignoreUnknownKeys = true
    }

    @Serializable
    data class DeviceData(
        @SerialName("udid")
        val deviceId: String,
        val isAvailable: Boolean,

        val deviceTypeIdentifier: String,
        val state: String,
        val name: String,
    )

    @Serializable
    data class SimCtlListOutput(
        @SerialName("devices")
        val runtimeIdToDevices: Map<String, List<DeviceData>>,
    )

    @Serializable
    data class SimCtlRuntimesOutput(
        val runtimes: List<RuntimeData>,
    )

    @Serializable
    data class RuntimeData(
        val identifier: String,
        val version: String,
        val isAvailable: Boolean,
        val supportedArchitectures: List<String> = [],
        val supportedDeviceTypes: List<DeviceTypeData> = [],
    )

    @Serializable
    data class DeviceTypeData(
        val name: String,
        val identifier: String,
        val productFamily: String,
    )

    sealed interface DeviceFilter {
        data object Available : DeviceFilter
        data class DeviceId(val id: XcodeDeviceId) : DeviceFilter
    }

    context(_: ProcessRunner)
    suspend fun queryDevices(filter: DeviceFilter? = null): SimCtlListOutput {
        val args: MutableList<String> = [
            "simctl", "list", "devices", "--json",
        ]
        when (filter) {
            DeviceFilter.Available -> args += "available"
            is DeviceFilter.DeviceId -> args += filter.id.value
            null -> Unit
        }
        val simctlListOut = xcrun(*args.toTypedArray(), outputMode = ProcessOutputMode.capture()).stdout
        return SimCtlOutputFormat.decodeFromString<SimCtlListOutput>(simctlListOut)
    }

    context(_: ProcessRunner)
    suspend fun listAvailableRuntimes(): SimCtlRuntimesOutput {
        val runtimesOutput = xcrun(
            "simctl", "list", "runtimes", "--json", "available",
            outputMode = ProcessOutputMode.captureMergedStreams(),
        )
        return SimCtlOutputFormat.decodeFromString<SimCtlRuntimesOutput>(runtimesOutput.stdoutAndStderr)
    }

    context(_: ProcessRunner)
    suspend fun create(
        name: String,
        deviceTypeId: String,
        runtimeId: String,
    ): String = xcrun(
        "simctl", "create", name, deviceTypeId, runtimeId,
        outputMode = ProcessOutputMode.capture(),
    ).stdout.trim()

    context(_: ProcessRunner)
    suspend fun boot(deviceId: String) = xcrun("simctl", "boot", deviceId)

    context(_: ProcessRunner)
    suspend fun install(deviceId: String, appPath: Path) = xcrun("simctl", "install", deviceId, appPath.pathString)

    context(_: ProcessRunner)
    suspend fun launch(deviceId: String, bundleId: String) = xcrun("simctl", "launch", deviceId, bundleId)

    context(_: ProcessRunner)
    suspend fun shutdown(deviceId: String) = xcrun("simctl", "shutdown", deviceId)
}

private object DeviceCtl : Xcrun() {
    context(_: ProcessRunner)
    suspend fun installApp(
        deviceId: String,
        appPath: Path,
    ) = xcrun("devicectl", "device", "install", "app", "--device", deviceId, appPath.pathString)

    context(_: ProcessRunner)
    suspend fun launchProcess(
        deviceId: String,
        bundleId: String,
    ) = xcrun("devicectl", "device", "process", "launch", "--device", deviceId, bundleId)
}

private const val IPHONE_PRODUCT_FAMILY = "iPhone"
private const val IOS_SIMULATOR_RUNTIME_ID_PREFIX = "com.apple.CoreSimulator.SimRuntime.iOS-"

private val IPhoneModelNumberRegex = """iPhone-([0-9]+)""".toRegex()

private val logger = LoggerFactory.getLogger("org.jetbrains.amper.tasks.ios")