/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds

const val XCRUN_EXECUTABLE = "/usr/bin/xcrun"

context(_: XcodeEnvironment)
suspend fun ProcessRunner.installAppOnDevice(deviceId: XcodeDeviceId, appPath: Path) =
    SimCtl.install(deviceId = deviceId.value, appPath = appPath)

context(_: XcodeEnvironment)
suspend fun ProcessRunner.installAppOnPhysicalDevice(deviceId: XcodeDeviceId, appPath: Path) =
    DeviceCtl.installApp(deviceId = deviceId.value, appPath = appPath)

context(_: XcodeEnvironment)
suspend fun ProcessRunner.launchAppOnDevice(
    deviceId: XcodeDeviceId,
    bundleId: String,
    waitForDebugger: Boolean = false,
): Long? = SimCtl.launch(deviceId = deviceId.value, bundleId = bundleId, waitForDebugger = waitForDebugger)

context(_: XcodeEnvironment)
suspend fun ProcessRunner.launchAppOnPhysicalDevice(
    deviceId: XcodeDeviceId,
    bundleId: String,
    startStopped: Boolean = false,
): Long? = DeviceCtl.launchProcess(deviceId = deviceId.value, bundleId = bundleId, startStopped = startStopped)

/**
 * Suspends until the simulator app process with the given [pid] terminates.
 */
suspend fun waitForSimulatorAppExit(pid: Long) {
    while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)!!) {
        delay(500.milliseconds)
    }
}

/**
 * Suspends until the app process with the given [pid] terminates on the physical device.
 */
context(_: XcodeEnvironment)
suspend fun ProcessRunner.waitForPhysicalDeviceAppExit(deviceId: XcodeDeviceId, pid: Long) {
    while (DeviceCtl.isProcessRunning(deviceId.value, pid)) {
        delay(2000.milliseconds)
    }
}

context(_: XcodeEnvironment)
suspend fun ProcessRunner.shutdownDevice(deviceId: XcodeDeviceId) =
    SimCtl.shutdown(deviceId = deviceId.value)

context(_: XcodeEnvironment)
suspend fun ProcessRunner.latestAvailableRuntimeVersion(): ComparableVersion {
    return SimCtl.listAvailableRuntimes().runtimes.maxOfOrNull { ComparableVersion(it.version) }
        ?: ComparableVersion("0.0")
}

context(_: XcodeEnvironment)
suspend fun ProcessRunner.latestSimulatorSdkVersion(): ComparableVersion {
    return Xcodebuild.showSdks()
        .filter { it.platform == XcodebuildPlatform.`iOS Simulator`.sdk }
        .maxOfOrNull { ComparableVersion(it.platformVersion) }
        ?: ComparableVersion("0.0")
}

context(_: XcodeEnvironment)
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

context(_: XcodeEnvironment)
suspend fun ProcessRunner.isSimulatorBooted(
    deviceId: XcodeDeviceId,
): Boolean {
    val device = SimCtl.queryDevices(SimCtl.DeviceFilter.DeviceId(deviceId))
        .runtimeIdToDevices.entries.flatMap { [_, devices] -> devices }.singleOrNull()
        ?: userReadableError("Simulator device $deviceId disappeared unexpectedly")
    return device.state.lowercase() == "booted"
}

// We assume that the device is not yet booted
context(_: XcodeEnvironment)
suspend fun ProcessRunner.ensureSimulatorBooted(
    deviceId: XcodeDeviceId,
    forceShowWindow: Boolean = false,
    failIfAlreadyBooted: Boolean = false,
) = coroutineScope {
    val booted by lazy {
        async {
            // we could also use `simctl bootstatus -b` which boots and waits on its own
            SimCtl.boot(deviceId.value, failIfAlreadyBooted)
        }
    }

    repeat(20) {
        if (isSimulatorBooted(deviceId)) {
            if (forceShowWindow) {
                openSimulatorWindow(deviceId)
            }
            return@coroutineScope  // Success
        }
        booted.await()
        delay(500.milliseconds)
    }
    userReadableError("Simulator boot timeout for `${deviceId}`.")
}

context(xcodeEnvironment: XcodeEnvironment)
private suspend fun ProcessRunner.openSimulatorWindow(deviceId: XcodeDeviceId) {
    val command = buildList {
        add("open")
        add("-a")
        if (xcodeEnvironment.version >= ComparableVersion("27.0")) {
            // Xcode 27.0+ comes without the Simulator.app but the `DeviceHub.app`.
            // Ensure the window for the selected simulator is shown.
            add(xcodeEnvironment.developerDirectory.parent.resolve("Applications/DeviceHub.app").absolutePathString())
            add("devices://device/open?id=${deviceId.value}")
        } else {
            // The `open` command works without any errors/warnings regardless of the simulator boot status.
            // It boots the simulator on demand and brings its window forward.
            add(xcodeEnvironment.developerDirectory.resolve("Applications/Simulator.app").absolutePathString())
            add("--args")
            add("-CurrentDeviceUDID")
            add(deviceId.value)
        }
    }
    runProcess(
        workingDir = Path("."),
        command = command,
        outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger)),
    )
}

context(_: XcodeEnvironment)
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
    context(_: ProcessRunner, _: XcodeEnvironment)
    protected suspend fun xcrun(vararg args: String, checkNonZeroExitCode: Boolean = true): ProcessResult.WithStderr =
        xcrun(
            *args,
            outputMode = ProcessOutputMode.listenAndCaptureStderr(LoggingProcessOutputListener(logger)),
            checkNonZeroExitCode = checkNonZeroExitCode,
        )

    context(processRunner: ProcessRunner, xcodeEnvironment: XcodeEnvironment)
    protected suspend fun <R : ProcessResult> xcrun(
        vararg args: String,
        outputMode: ProcessOutputMode<R>,
        checkNonZeroExitCode: Boolean = true,
    ): R = processRunner.runProcess(
        workingDir = Path("."),
        command = listOf(XCRUN_EXECUTABLE) + args,
        configureEnvironment = { xcodeEnvironment.configureCommandEnvironment() },
        outputMode = outputMode,
    ).also {
        if (checkNonZeroExitCode && it.exitCode.value != 0) {
            userReadableError("xcrun `${args.contentToString()}` failed with exit code ${it.exitCode}")
        }
    }
}

private object Xcodebuild : Xcrun() {
    private val OutputFormat = Json {
        ignoreUnknownKeys = true
    }

    @Serializable
    data class SdkData(
        val platform: String,
        val platformVersion: String,
    )

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun showSdks(): List<SdkData> {
        val result = xcrun("xcodebuild", "-showsdks", "-json", outputMode = ProcessOutputMode.capture())
        return OutputFormat.decodeFromString(result.stdout)
    }
}

/**
 * Parses the PID of the launched process from the output of `xcrun simctl launch`,
 * which prints `<bundle id>: <pid>`.
 *
 * Only the line starting with `<bundle id>: ` is considered, so that unrelated lines
 * (e.g. warnings) ending with a number are not mistaken for the PID.
 */
internal fun parseSimctlLaunchPid(stdout: String, bundleId: String): Long? {
    val pidLinePrefix = "$bundleId: "
    return stdout.lineSequence()
        .firstNotNullOfOrNull { line ->
            if (line.startsWith(pidLinePrefix)) line.removePrefix(pidLinePrefix).trim().toLongOrNull() else null
        }
}

@Serializable
private data class DevicectlLaunchOutput(
    val result: DevicectlLaunchResult? = null,
)

@Serializable
private data class DevicectlLaunchResult(
    val process: DevicectlProcessData? = null,
)

@Serializable
private data class DevicectlProcessesOutput(
    val result: DevicectlProcessesResult? = null,
)

@Serializable
private data class DevicectlProcessesResult(
    val runningProcesses: List<DevicectlProcessData> = [],
)

@Serializable
private data class DevicectlProcessData(
    val processIdentifier: Long? = null,
)

private val DevicectlOutputFormat = Json { ignoreUnknownKeys = true }

/**
 * Parses the PID of the launched process from the JSON output of `xcrun devicectl device process launch`.
 *
 * Returns `null` if the output cannot be parsed.
 */
internal fun parseDevicectlLaunchPid(json: String): Long? = try {
    DevicectlOutputFormat.decodeFromString<DevicectlLaunchOutput>(json).result?.process?.processIdentifier
} catch (e: SerializationException) {
    logger.warn("Failed to parse devicectl launch output", e)
    null
}

/**
 * Parses the process identifiers from the JSON output of `xcrun devicectl device info processes`.
 *
 * Returns an empty list if the output cannot be parsed.
 */
internal fun parseDevicectlRunningProcessIds(json: String): List<Long> = try {
    DevicectlOutputFormat.decodeFromString<DevicectlProcessesOutput>(json)
        .result?.runningProcesses?.mapNotNull { it.processIdentifier } ?: []
} catch (e: SerializationException) {
    logger.warn("Failed to parse devicectl processes output", e)
    []
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

    context(_: ProcessRunner, _: XcodeEnvironment)
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

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun listAvailableRuntimes(): SimCtlRuntimesOutput {
        val runtimesOutput = xcrun(
            "simctl", "list", "runtimes", "--json", "available",
            outputMode = ProcessOutputMode.captureMergedStreams(),
        )
        return SimCtlOutputFormat.decodeFromString<SimCtlRuntimesOutput>(runtimesOutput.stdoutAndStderr)
    }

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun create(
        name: String,
        deviceTypeId: String,
        runtimeId: String,
    ): String = xcrun(
        "simctl", "create", name, deviceTypeId, runtimeId,
        outputMode = ProcessOutputMode.capture(),
    ).stdout.trim()

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun boot(deviceId: String, failIfAlreadyBooted: Boolean = false) {
        val result = xcrun("simctl", "boot", deviceId, checkNonZeroExitCode = false)
        if ("Unable to boot device in current state: Booted" in result.stderr) {
            if (failIfAlreadyBooted) {
                userReadableError("Simulator for device ID $deviceId is already booted")
            } else {
                return
            }
        }
        if (result.exitCode.value != 0) {
            userReadableError("Failed to boot simulator: command `${result.command.joinToString(" ")}` failed with " +
                    "exit code ${result.exitCode}")
        }
        return
    }

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun install(deviceId: String, appPath: Path) = xcrun("simctl", "install", deviceId, appPath.pathString)

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun launch(deviceId: String, bundleId: String, waitForDebugger: Boolean = false): Long? {
        val result = xcrun(
            "simctl", "launch", *(if (waitForDebugger) ["--wait-for-debugger"] else []), deviceId, bundleId,
            outputMode = ProcessOutputMode.capture(),
        )
        return parseSimctlLaunchPid(result.stdout, bundleId)
    }

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun shutdown(deviceId: String) = xcrun("simctl", "shutdown", deviceId)
}

private object DeviceCtl : Xcrun() {
    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun installApp(
        deviceId: String,
        appPath: Path,
    ) = xcrun("devicectl", "device", "install", "app", "--device", deviceId, appPath.pathString)

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun launchProcess(
        deviceId: String,
        bundleId: String,
        startStopped: Boolean = false,
    ): Long? {
        val jsonOutput = createTempFile("devicectl-launch-", ".json")
        try {
            xcrun(
                "devicectl", "device", "process", "launch", "--device", deviceId,
                "--json-output", jsonOutput.pathString,
                *(if (startStopped) arrayOf("--start-stopped") else emptyArray()), bundleId,
            )
            return parseDevicectlLaunchPid(jsonOutput.readText())
        } finally {
            jsonOutput.deleteIfExists()
        }
    }

    context(_: ProcessRunner, _: XcodeEnvironment)
    suspend fun isProcessRunning(deviceId: String, pid: Long): Boolean {
        val jsonOutput = createTempFile("devicectl-processes-", ".json")
        try {
            xcrun(
                "devicectl", "device", "info", "processes", "--device", deviceId,
                "--json-output", jsonOutput.pathString,
            )
            return pid in parseDevicectlRunningProcessIds(jsonOutput.readText())
        } finally {
            jsonOutput.deleteIfExists()
        }
    }
}

private const val IPHONE_PRODUCT_FAMILY = "iPhone"
private const val IOS_SIMULATOR_RUNTIME_ID_PREFIX = "com.apple.CoreSimulator.SimRuntime.iOS-"

private val IPhoneModelNumberRegex = """iPhone-([0-9]+)""".toRegex()

private val logger = LoggerFactory.getLogger("org.jetbrains.amper.tasks.ios")