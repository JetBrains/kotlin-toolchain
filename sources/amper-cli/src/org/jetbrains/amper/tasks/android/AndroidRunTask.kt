/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.prefs.AndroidLocationsSingleton
import com.android.repository.api.ConsoleProgressIndicator
import com.android.sdklib.AndroidVersion
import com.android.sdklib.AndroidVersion.VersionCodes.UPSIDE_DOWN_CAKE
import com.android.sdklib.devices.DeviceManager
import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.AvdManager
import com.android.sdklib.internal.avd.OnDiskSkin
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.utils.StdLogger
import org.jetbrains.amper.android.tools.AndroidEmulatorFailedException
import org.jetbrains.amper.android.tools.AndroidTools
import org.jetbrains.amper.android.tools.EmulatorBootFailureException
import org.jetbrains.amper.android.tools.awaitBootCompleted
import org.jetbrains.amper.android.tools.manifest.findMainLauncherActivity
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.RunTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.singleSourceRoot
import org.jetbrains.amper.processes.ProcessLeak
import org.jetbrains.amper.tasks.MobileRunSettings
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString
import org.jetbrains.amper.frontend.schema.AndroidVersion as AmperAndroidVersion

const val headlessEmulatorModePropertyName = "org.jetbrains.amper.android.emulator.headless"

class AndroidRunTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    override val buildType: BuildType,
    private val runSettings: MobileRunSettings,
    private val androidSdkPath: Path,
    private val avdPath: Path,
) : RunTask {
    override val platform: Platform
        get() = Platform.ANDROID

    private val fragments = module.fragments.filter { !it.isTest && it.platforms.contains(platform) }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val androidFragment = fragments.filterIsInstance<LeafFragment>().singleOrNull()
            ?: error("Only one $platform fragment is expected")

        val emulatorExecutable = (dependenciesResult
            .filterIsInstance<GetAndroidPlatformFileFromPackageTask.Result>()
            .flatMap { it.outputs.filter { it.endsWith("emulator") } }.singleOrNull()
            ?: error("Emulator not found")).resolve("emulator")

        val androidTools = AndroidTools(androidSdkPath, avdPath, emulatorExecutable)
        val adb = androidTools.getOrCreateDebugBridge()

        val androidVersion = AndroidVersion(androidFragment.settings.android.targetSdk.versionNumber, 0)
        val device = adb.findEmulatorOrDevice(androidVersion) ?: startNewEmulator(androidVersion, androidTools)
        try {
            device.awaitBootCompleted()
        } catch (e: EmulatorBootFailureException) {
            userReadableError(message = e.message, cause = e.cause)
        }

        val apk = dependenciesResult.filterIsInstance<AndroidDelegatedGradleTask.Result>()
            .singleOrNull()?.artifacts?.firstOrNull() ?: error("Apk not found")

        // https://developer.android.com/about/versions/14/behavior-changes-all
        val unsupportedMinSdk = androidFragment.settings.android.minSdk <= AmperAndroidVersion(23)
        val modernAndroidVersion = device.version >= AndroidVersion(UPSIDE_DOWN_CAKE, null)
        val extraArgs = if (unsupportedMinSdk && modernAndroidVersion) {
            arrayOf("--bypass-low-target-sdk-block")
        } else {
            emptyArray<String>()
        }

        device.installPackage(apk.pathString, true, *extraArgs)

        val activityName = findActivityToLaunch(androidFragment) ?: userReadableError("Could not find activity to launch")

        val outputReceiver = CollectingOutputReceiver()
        val applicationId = checkNotNull(androidFragment.settings.android.applicationId) {
            "Application ID should've been set for the module ${module.userReadableName} and verified by the frontend"
        }
        device.executeShellCommand(
            "am start -a android.intent.action.MAIN -n $applicationId/$activityName",
            outputReceiver
        )

        outputReceiver.output
            .split("\n", "\r")
            .filter { it.isNotBlank() }
            .forEach {
                if ("Error" in it) {
                    logger.error(it)
                } else {
                    logger.info(it)
                }
            }

        return Result(device)
    }

    private fun AndroidDebugBridge.findEmulatorOrDevice(
        androidVersion: AndroidVersion,
    ): IDevice? {
        val deviceId = runSettings.deviceId
        return if (deviceId == null) {
            devices.firstOrNull { it.version.canRun(androidVersion) }
        } else {
            devices.find { it.serialNumber == deviceId }
                ?: userReadableError("Unable to find the device with the serial = `$deviceId`, available devices: " +
                                         devices.joinToString { it.serialNumber })
        }
    }

    private suspend fun startNewEmulator(
        androidVersion: AndroidVersion,
        androidTools: AndroidTools,
    ): IDevice {
        val sdkHandler = AndroidSdkHandler.getInstance(AndroidLocationsSingleton, androidSdkPath)
        val deviceManager = DeviceManager.createInstance(sdkHandler, StdLogger(StdLogger.Level.VERBOSE))
        val avdManager = AvdManager.createInstance(sdkHandler, avdPath, deviceManager, StdLogger(StdLogger.Level.VERBOSE))
        val avd = avdManager.validAvds.firstOrNull { it.androidVersion.canRun(androidVersion) }
            ?: createNewAvd(sdkHandler, androidVersion, avdManager)

        return try {
            @OptIn(ProcessLeak::class) // we start the emulator as long-lived on purpose to avoid paying startup time
            androidTools.startEmulatorAndAwaitOnline(
                avdName = avd.name,
                androidVersion = androidVersion,
                headless = System.getProperty(headlessEmulatorModePropertyName).toBoolean()
            )
        } catch (e: AndroidEmulatorFailedException) {
            userReadableError(e.message)
        }
    }

    private fun createNewAvd(
        sdkHandler: AndroidSdkHandler,
        androidVersion: AndroidVersion,
        avdManager: AvdManager,
    ): AvdInfo {
        val consoleProgressIndicator = ConsoleProgressIndicator()
        val systemImageManager = sdkHandler.getSystemImageManager(consoleProgressIndicator)
        val systemImage = systemImageManager.images.firstOrNull { it.androidVersion.canRun(androidVersion) }
            ?: error("System image for $androidVersion not found")
        val systemImageVersion = systemImage.androidVersion.apiStringWithoutExtension
        // NB: Skin + AVD name + hardware config should match the phone specifications
        // See Device Manager for specifications
        return avdManager.createAvd(
            avdFolder = avdPath.resolve("Pixel-9-$systemImageVersion-ktc.avd"),
            avdName = "Pixel 9 API $systemImageVersion",
            systemImage = systemImage,
            skin = androidSdkPath.resolve("skins/pixel_9")
                .takeIf { it.exists() }
                ?.let(::OnDiskSkin),
            sdcard = null,
            hardwareConfig = mutableMapOf(
                "hw.lcd.width" to "1080",
                "hw.lcd.height" to "2424",
                "hw.lcd.density" to "420",
            ),
            userSettings = mutableMapOf(),
            bootProps = mutableMapOf(),
            environment = mutableMapOf(),
            deviceHasPlayStore = true,
            removePrevious = true,
            editExisting = true,
        )
    }

    private fun findActivityToLaunch(androidFragment: LeafFragment): String? {
        // It is safe to assume android/app has only one source directory because the maven-like layout is not supported for them
        val manifestPath = androidFragment
            .singleSourceRoot("Android application must have a single source root")
            .resolve("AndroidManifest.xml")

        if (!manifestPath.exists()) {
            userReadableError("AndroidManifest.xml not found in ${manifestPath.parent}")
        }
        return findMainLauncherActivity(manifestPath)
    }

    data class Result(val device: IDevice) : TaskResult
}
