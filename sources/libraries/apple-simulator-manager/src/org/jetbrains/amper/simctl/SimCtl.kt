/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.simctl

import kotlinx.serialization.json.Json
import org.jetbrains.amper.processes.runProcessAndCaptureOutput
import org.jetbrains.amper.simctl.model.SimDevice
import org.jetbrains.amper.simctl.model.SimDeviceId
import org.jetbrains.amper.simctl.model.SimDeviceType
import org.jetbrains.amper.simctl.model.SimDeviceTypes
import org.jetbrains.amper.simctl.model.SimDevices
import org.jetbrains.amper.simctl.model.SimRuntime
import org.jetbrains.amper.simctl.model.SimRuntimeId
import org.jetbrains.amper.simctl.model.SimRuntimes
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Type-safe wrapper over `xcrun simctl` commands.
 */
object SimCtl {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lists the devices available for each of the installed simulator runtimes.
     *
     * Use [bootedOnly] to filter the result to show only devices that are currently booted.
     */
    suspend fun listDevices(bootedOnly: Boolean = false): Map<SimRuntimeId, List<SimDevice>> {
        val result = runProcessAndCaptureOutput(
            command = buildList {
                addAll(["xcrun", "simctl", "list", "devices", "--json"])
                if (bootedOnly) {
                    add("booted")
                }
            }
        )
        if (result.exitCode != 0) {
            throw SimCtlException("Failed to retrieve devices list: ${result.stderr}")
        }
        return json.decodeFromString<SimDevices>(result.stdout).devices
    }

    /**
     * Lists the device types available on the current machine.
     *
     * This is useful to get more information about a device using its [SimDevice.deviceTypeId].
     */
    suspend fun listDeviceTypes(): List<SimDeviceType> {
        val result = runProcessAndCaptureOutput(
            command = ["xcrun", "simctl", "list", "devicetypes", "--json"]
        )
        if (result.exitCode != 0) {
            throw SimCtlException("Failed to retrieve device types list: ${result.stderr}")
        }
        return json.decodeFromString<SimDeviceTypes>(result.stdout).devicetypes
    }

    /**
     * Lists the simulator runtimes (iOS, watchOS, tvOS, etc.) installed on the current machine.
     */
    suspend fun listRuntimes(): List<SimRuntime> {
        val result = runProcessAndCaptureOutput(
            command = ["xcrun", "simctl", "list", "runtimes", "--json"]
        )
        if (result.exitCode != 0) {
            throw SimCtlException("Failed to retrieve runtimes list: ${result.stderr}")
        }
        return json.decodeFromString<SimRuntimes>(result.stdout).runtimes
    }

    /**
     * Boots a simulator with the given [deviceId].
     *
     * If the simulator is already in 'Booted' state, this function throws an exception unless [failIfAlreadyBooted] is
     * set to `false`.
     *
     * @throws SimulatorAlreadyBootedException If the device is already booted, and [failIfAlreadyBooted] is `true`.
     * @throws SimulatorBootException If the device wasn't already booted, but failed to boot.
     */
    suspend fun bootSimulator(deviceId: SimDeviceId, failIfAlreadyBooted: Boolean = true) {
        val result = runProcessAndCaptureOutput(command = ["xcrun", "simctl", "boot", deviceId.value])
        if ("Unable to boot device in current state: Booted" in result.stderr) {
            if (failIfAlreadyBooted) {
                throw SimulatorAlreadyBootedException(deviceId)
            } else {
                return
            }
        }
        if (result.exitCode != 0) {
            throw SimulatorBootException(deviceId = deviceId, stderr = result.stderr)
        }
    }

    /**
     * Returns whether the simulator with the specified [deviceId] is currently running.
     */
    suspend fun isSimulatorRunning(deviceId: SimDeviceId): Boolean {
        val bootedDevices = listDevices(bootedOnly = true).values.flatten()
        return bootedDevices.any { it.id == deviceId }
    }

    /**
     * Installs the app from the given [appFile] onto the given [device].
     */
    suspend fun installApp(appFile: Path, device: TargetDevice = TargetDevice.AnyBootedDevice) {
        val result = runProcessAndCaptureOutput(
            command = ["xcrun", "simctl", "install", device.cliName, appFile.absolutePathString()],
        )
        if (result.exitCode != 0) {
            throw AppInstallationException(appFile, device, result.stderr)
        }
    }

    /**
     * Uninstalls the app with the given [appBundleId] from the given [device].
     */
    suspend fun uninstallApp(appBundleId: String, device: TargetDevice = TargetDevice.AnyBootedDevice) {
        val result = runProcessAndCaptureOutput(command = ["xcrun", "simctl", "uninstall", device.cliName, appBundleId])
        if (result.exitCode != 0) {
            throw AppUninstallationException(appBundleId, device, result.stderr)
        }
    }

    /**
     * Launches the app with the given [appBundleId] on the given [device].
     *
     * @return the PID of the app's process on the device.
     */
    suspend fun launchApp(appBundleId: String, device: TargetDevice = TargetDevice.AnyBootedDevice): Int {
        val launchOutput = runProcessAndCaptureOutput(
            command = ["xcrun", "simctl", "launch", device.cliName, appBundleId],
            redirectErrorStream = true,
        )

        // Check for errors in launch output based on specific error keywords
        val errorKeywords = ["error", "failed", "FBSOpenApplicationServiceErrorDomain"]
        if (errorKeywords.any { launchOutput.stdout.contains(it, ignoreCase = true) }) {
            throw AppLaunchException(appBundleId, device, launchOutput.stdout)
        }

        val appWithPidRegex = Regex("""${Regex.escape(appBundleId)}:\s*(?<pid>\d+)""")
        val match = appWithPidRegex.find(launchOutput.stdout)
            ?: error("Missing launch PID in output. Launch output:\n${launchOutput.stdout}")

        return match.groups["pid"]?.value?.toInt() ?: error("Regex matched without 'pid' group")
    }
}

/**
 * A target device for app installation, uninstallation, and launch.
 */
sealed interface TargetDevice {
    /**
     * This means that any booted device can be used.
     *
     * It should be used when you expect a simulator to be already running, and you don't know its device ID.
     */
    object AnyBootedDevice : TargetDevice

    /**
     * References a specific device with the given [id].
     */
    data class SpecificDevice(val id: SimDeviceId) : TargetDevice
}

private val TargetDevice.cliName: String get() = when (this) {
    TargetDevice.AnyBootedDevice -> "booted"
    is TargetDevice.SpecificDevice -> id.value
}
