/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package androidUtils

import TestBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.jetbrains.amper.test.android.AndroidTools
import org.jetbrains.amper.test.android.Emulator
import org.jetbrains.amper.test.processes.TestReporterProcessOutputListener
import org.jetbrains.amper.test.processes.checkExitCodeIsZero
import org.jetbrains.amper.test.runTestWithMdc
import java.nio.file.Path
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Main test class that provides methods to run Android tests.
 */
// NOT PARALLEL ON PURPOSE
open class AndroidBaseTest : TestBase() {

    private val androidTools by lazy { runBlocking(MDCContext()) { AndroidTools.prepareForTests() } }

    /**
     * Executes instrumented tests for the Android project specified by [projectSource],
     * optionally using [applicationId] for custom APK setups.
     *
     * @param androidAppModuleName android app module name inside the project;
     *   if `null` then the root module is assumed to be the android module.
     */
    protected fun runInstrumentedTests(
        projectSource: ProjectSource,
        applicationId: String? = null,
        androidAppModuleName: String? = null,
    ) = runTestWithMdc(timeout = 15.minutes) {

        val copiedProjectDir = copyProjectToTempDir(projectSource)
        val targetApkPath = buildApkWithAmper(copiedProjectDir, moduleName = androidAppModuleName ?: copiedProjectDir.name)
        val testApp = InstrumentedTestApp.assemble(testReporter)

        // This dispatcher switch is not superstition. The test dispatcher skips delays by default.
        // We interact with real external processes here, so we can't skip delays when we do retries.
        withContext(Dispatchers.IO) {
            androidTools.withEmulator {
                // The host app of the instrumentation must be installed too, because the instrumentation runs in its
                // process. Without it, 'am instrument' fails to find the target package of the instrumentation.
                println("Installing host app of the instrumented tests (${testApp.hostApk})")
                installApk(testApp.hostApk)
                println("Installing test app containing instrumented tests (${testApp.instrumentationApk})")
                installApk(testApp.instrumentationApk)
                println("Installing target app from test project ($targetApkPath)")
                installApk(targetApkPath)
                println("Running tests via adb...")
                runTestsViaAdb(applicationId)
            }
        }
    }

    private suspend fun Emulator.runTestsViaAdb(applicationId: String? = null) {
        // disable all animations on the emulator to speed up test execution.
        adbShell("settings", "put", "global", "window_animation_scale", "0.0")
        adbShell("settings", "put", "global", "transition_animation_scale", "0.0")
        adbShell("settings", "put", "global", "animator_duration_scale", "0.0")
        adbShell("settings", "put", "secure", "long_press_timeout", "1000")
        // After it executes tests using the specified test package name,
        // falling back to a default package if none is provided
        val targetAppPackage = applicationId ?: "com.jetbrains.sample.app"
        val testAppPackage = "com.jetbrains.sample.testapp.test"
        val testRunnerFqn = "androidx.test.runner.AndroidJUnitRunner"

        val output = adbShell(
            "am",
            "instrument",
            "-w",
            "-e", "targetPackage", targetAppPackage,
            "-r",
            "$testAppPackage/$testRunnerFqn",
        )
        if (!output.contains("OK (1 test)")) {
            failTestWithAppDiagnostics(output, "Test output doesn't contain 'OK (1 test)'")
        } else if (output.contains("Error")) {
            failTestWithAppDiagnostics(output, "Test failed with 'Error'")
        }
    }

    private suspend fun Emulator.failTestWithAppDiagnostics(output: String, message: String): Nothing {
        val nSecondsAgo = 15
        val logCatOutput = logcatLastNSeconds(nSecondsAgo).prependIndent("[logcat] ")
        fail("$message\n\nEmulator errors/warnings in the last $nSecondsAgo seconds of logs:\n\n${logCatOutput}\n\nTest output:\n\n$output")
    }

    /**
     * Executes the given adb shell [command] and returns the output.
     */
    private suspend fun Emulator.adbShell(vararg command: String): String {
        val outputListener = TestReporterProcessOutputListener("adb shell", testReporter)
        return adb("shell", *command, outputListener = outputListener).checkExitCodeIsZero().stdout
    }

    /**
     * Builds the Android debug APK for the given [moduleName] in the given [projectDir].
     *
     * @return the path to the built APK
     */
    private suspend fun buildApkWithAmper(projectDir: Path, moduleName: String): Path {
        runAmper(
            workingDir = projectDir,
            args = listOf("task", ":$moduleName:buildAndroidDebug"),
            environment = androidTools.environment() + mapOf(
                "AMPER_NO_GRADLE_DAEMON" to "1", // ensures we don't leak the daemon
            ),
        )
        // internal Amper convention based on the task name
        return projectDir / "build/tasks/_${moduleName}_buildAndroidDebug/gradle-project-debug.apk"
    }
}

private suspend fun <T> AndroidTools.withEmulator(block: suspend Emulator.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        returnsResultOf(block)
    }
    val testAvdName = "amper-test-avd"
    ensureAvdExists(testAvdName)
    return withEmulator(testAvdName) { block() }
}
