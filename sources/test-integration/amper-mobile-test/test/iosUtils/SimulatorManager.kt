/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package iosUtils

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.processes.ProcessLeak
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.processes.startLongLivedProcess
import org.jetbrains.amper.simctl.SimCtl
import org.jetbrains.amper.simctl.model.ProductFamilies
import org.jetbrains.amper.simctl.model.SimDevice
import org.jetbrains.amper.simctl.model.SimDeviceId
import org.jetbrains.amper.simctl.model.SimulatorPlatforms
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the iOS emulator lifecycle and provides helper methods
 */
internal object SimulatorManager {
    private val simulatorPath by lazy {
        runBlocking {
            Path(runProcess(command = listOf("xcode-select", "-p"), outputMode = ProcessOutputMode.capture()).stdout.trim()).resolve("Applications/Simulator.app")
        }
    }

    /**
     * Launches the iOS Simulator with the specified device.
     */
    @ProcessLeak
    suspend fun launchLatestIPhoneSimulator(): SimDeviceId {
        if (!simulatorPath.exists()) {
            error("Simulator app not found at $simulatorPath")
        }

        // The iOS Simulator that is launched must be the latest version available in the system because the Kotlin
        // Toolchain builds the app file targeting the highest available iOS version by default.

        val deviceId = getLatestIPhoneWithLatestIOS()
        SimCtl.bootSimulator(deviceId, failIfAlreadyBooted = false)

        startLongLivedProcess(command = listOf("open", simulatorPath.pathString))

        repeat(3) { attempt ->
            delay(5.seconds)

            if (SimCtl.isSimulatorRunning(deviceId)) {
                println("Simulator is now running.")
                return deviceId
            }
            println("Attempt ${attempt + 1}: Simulator not yet initialized, retrying...")
        }

        error("Simulator failed to start or initialize after multiple attempts.")
    }

    private suspend fun getLatestIPhoneWithLatestIOS(): SimDeviceId {
        val devicesForLatestIOS = getDevicesForLatestIos()
        val iPhoneTypesById = SimCtl.listDeviceTypes()
            .filter { it.productFamily == ProductFamilies.IPhone }
            .associateBy { it.id }

        // There is no machine-readable version for devices that would allow us to pick the latest. The names cannot
        // really be used to sort versions, because we would have to encode how 'iPhone SE' compares to 'iPhone 16'.
        // After all, we don't need the latest device, we just want to avoid obsolete ones and find a decently modern,
        // so we use a heuristic here based on the supported runtime versions of the device type.
        // There seems to be more differences in `minRuntimeVersion` than `maxRuntimeVersion`, so maximizing the minimum
        // version seems to be a better way to find a more modern device.
        val device = devicesForLatestIOS.maxBy { iPhoneTypesById[it.deviceTypeId]?.minRuntimeVersion ?: 0 }

        println("Resolved device for latest installed iOS: ${device.name} (${device.id})")
        return device.id
    }

    private suspend fun getDevicesForLatestIos(): List<SimDevice> {
        val latestIOSRuntime = SimCtl.listRuntimes()
            .filter { it.platform == SimulatorPlatforms.IOS }
            .maxByOrNull { ComparableVersion(it.version) }
            ?: error("No iOS runtime installed. Run 'xcodebuild -downloadPlatform iOS' to install runtimes.")
        println("Latest installed iOS runtime: ${latestIOSRuntime.version}")

        return SimCtl.listDevices()[latestIOSRuntime.id]
            ?: error("No device for the latest iOS (${latestIOSRuntime.name}) installed. " +
                    "Check out 'xcrun simctl create help' to create some simulators.")
    }
}
