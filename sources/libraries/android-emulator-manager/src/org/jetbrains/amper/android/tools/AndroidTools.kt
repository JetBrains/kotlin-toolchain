/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.tools

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.IDevice.DeviceState
import com.android.sdklib.AndroidVersion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import org.jetbrains.amper.android.tools.adb.Adb
import org.jetbrains.amper.processes.ExitCode
import org.jetbrains.amper.processes.LongLivedProcess
import org.jetbrains.amper.processes.ProcessLeak
import org.jetbrains.amper.processes.startLongLivedProcess
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.milliseconds

class AndroidTools(
    private val androidHome: Path,
    private val avdHome: Path,
    private val emulatorExecutable: Path
) {
    private val adbExecutable = androidHome.resolve("platform-tools/adb")

    /**
     * Ensures an `adb` connection is available and ready.
     *
     * This waits until `adb` is connected, and the devices list is initialized.
     * So the returned [AndroidDebugBridge] can immediately be used to list [AndroidDebugBridge.devices].
     */
    suspend fun getOrCreateDebugBridge(): AndroidDebugBridge {
        AndroidDebugBridge.init(true)
        val adb = AndroidDebugBridge.getBridge()
            ?: AndroidDebugBridge.createBridge(adbExecutable.toString(), false, 30, TimeUnit.SECONDS)
            ?: error("Failed to create Android debug bridge")
        while (!adb.hasInitialDeviceList()) {
            delay(100.milliseconds)
        }
        return adb
    }

    @ProcessLeak
    suspend fun startEmulatorAndAwaitOnline(
        avdName: String,
        androidVersion: AndroidVersion,
        headless: Boolean,
    ): IDevice {
        val emulatorProcess = startEmulator(emulatorExecutable, avdName, headless)
        return coroutineScope {
            val deviceOnline = async {
                Adb.awaitDeviceChanged { device, _ ->
                    device.state == DeviceState.ONLINE && device.version.canRun(androidVersion)
                }
            }
            try {
                select {
                    deviceOnline.onAwait { it }
                    emulatorProcess.exitCode.onAwait { exitCode ->
                        throw AndroidEmulatorFailedException(exitCode)
                    }
                }
            } finally {
                // The emulator must outlive this command, so we only stop watching it, we never kill it.
                deviceOnline.cancel()
            }
        }
    }

    /**
     * Starts the Android emulator for the given [avdName], detached from the life of the current JVM so it survives
     * after this command terminates.
     */
    @ProcessLeak
    private fun startEmulator(
        emulatorExecutable: Path,
        avdName: String,
        headless: Boolean,
    ): LongLivedProcess = startLongLivedProcess(
        command = buildList {
            add(emulatorExecutable.pathString)
            if (headless) {
                add("-no-window")
            }
            add("-avd")
            add(avdName)
        },
        workingDir = emulatorExecutable.parent,
        configureEnvironment = {
            put("ANDROID_AVD_HOME", avdHome.toString())
            put("ANDROID_HOME", androidHome.toString())
        },
    )
}

class AndroidEmulatorFailedException(
    val exitCode: ExitCode,
    override val message: String = "The Android emulator terminated with exit code $exitCode",
) : Exception(message)
