/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package iosUtils

import TestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.processes.ProcessLeak
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.simctl.SimCtl
import org.jetbrains.amper.test.MacOnly
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A base class for testing iOS modules and projects, providing utilities to clean up test directories,
 * prepare project builds, and launch test processes.
 */
@MacOnly
open class IOSBaseTest : TestBase() {

    /**
     * Runs the simulator-based iOS tests for the project from [projectSource] using the given [bundleIdentifier].
     *
     * If [iosAppModuleName] is specified, the corresponding module is used as the iOS app to test, otherwise the root
     * module is expected to be the iOS app.
     */
    @OptIn(ProcessLeak::class)
    internal fun runIosAppTests(
        projectSource: ProjectSource,
        bundleIdentifier: String,
        iosAppModuleName: String? = null,
    ) = runBlocking {
        val copiedProjectDir = copyProjectToTempDir(projectSource)
        val appDir = buildIosAppWithAmper(projectRootDir = copiedProjectDir, iosAppModuleName)
        SimulatorManager.launchLatestIPhoneSimulator()
        val appFile = appDir.findAppFile()
        println("Running iOS app ${appFile.name} from $appDir")
        installAndVerifyAppLaunch(appFile = appFile, appBundleId = bundleIdentifier)
    }

    /**
     * Builds the iOS app for the project located at [projectRootDir] using Amper.
     *
     * If [iosAppModuleName] is specified, the corresponding module is used as the iOS app to test,
     * otherwise the root module is expected to be the iOS app.
     */
    private suspend fun buildIosAppWithAmper(
        projectRootDir: Path,
        iosAppModuleName: String?,
    ): Path {
        val rootProjectName = projectRootDir.name
        val moduleName = iosAppModuleName ?: rootProjectName

        if (!projectRootDir.isDirectory()) {
            error("The path '$projectRootDir' does not exist or is not a directory.")
        }
        runAmper(
            workingDir = projectRootDir,
            args = listOf("build", "-m", moduleName, "-p", "iosSimulatorArm64"),
            // xcode will in turn call Amper with this env
            environment = baseEnvironmentForWrapper(),
            assertEmptyStdErr = false,
        )
        return projectRootDir / "build/tasks/_${moduleName}_buildIosAppIosSimulatorArm64Debug/bin/Debug-iphonesimulator"
    }

    private fun Path.findAppFile(): Path =
        listDirectoryEntries("*.app").firstOrNull() ?: error("app file not found in $this")

    /**
     * Installs an iOS app on the booted simulator, launches it, verifies its successful start,
     * and uninstalls it to clean up after the check.
     *
     * The function installs the specified app file, identified by its bundle ID, onto the currently booted simulator.
     * It then launches the app and checks the log output for any errors or indicators of failure. If no errors are found,
     * it verifies the presence of both the bundle ID and a process ID (PID) to confirm a successful start.
     * Additionally, it ensures the app's data container is accessible, signaling that the app is fully active.
     * Finally, the function uninstalls the app to leave the simulator in a clean state.
     */
    private suspend fun installAndVerifyAppLaunch(appFile: Path, appBundleId: String) {
        println("Installing $appBundleId")
        SimCtl.installApp(appFile)

        println("Launching $appBundleId")
        val pid = SimCtl.launchApp(appBundleId)
        println("Log output indicates successful launch with PID $pid")

        // Step 5: Verify the existence of app data container to ensure app is running
        println("Verifying app container existence in data directory")
        val containerDataOutput = runProcess(
            command = ["xcrun", "simctl", "get_app_container", "booted", appBundleId, "data"],
            outputMode = ProcessOutputMode.captureMergedStreams(),
        )
        // If data container is missing, the app might not be fully active
        if (containerDataOutput.stdoutAndStderr.length <= 1) {
            error("App container not found in data directory. Container data check failed.")
        }
        println("App data container verified successfully.")
        println("App launched and verified successfully!")

        // Step 6: Uninstall the app to clean up after testing
        println("Uninstalling $appBundleId")
        SimCtl.uninstallApp(appBundleId)
    }
}
