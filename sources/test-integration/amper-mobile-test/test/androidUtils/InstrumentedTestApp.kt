/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package androidUtils

import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.android.AndroidTools
import org.jetbrains.amper.test.gradle.runGradle
import org.junit.jupiter.api.TestReporter
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Manages project preparation tasks such as copying and assembling the project.
 */
object InstrumentedTestApp  {

    /**
     * Path to the Gradle project containing the instrumented test app.
     * This app is installed next to the app under test on the same device, and is responsible for running the tests.
     */
    private val testAppProject = Dirs.amperSourcesRoot / "test-integration/amper-mobile-test/testData/instrumented-tests-app/app"

    /**
     * Assembles the APKs of the test app: the host APK and the APK containing the instrumented tests themselves.
     */
    suspend fun assemble(testReporter: TestReporter): TestAppApks {
        runGradle(
            projectDir = testAppProject,
            args = listOf("assembleDebug", "createDebugAndroidTestApk"),
            cmdName = "gradle (test-apk)",
            testReporter = testReporter,
            additionalEnv = AndroidTools.prepareForTests().environment(),
            gradleVersion = "9.1.0",
        )

        return TestAppApks(
            hostApk = testAppProject / "build/outputs/apk/debug/app-debug.apk",
            instrumentationApk = testAppProject / "build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
        )
    }
}

/**
 * The APKs of the instrumented test app. Both of them must be installed to be able to run the tests:
 * the instrumentation declared in [instrumentationApk] targets the application ID of [hostApk], and `am instrument`
 * fails with INSTRUMENTATION_FAILED if that target package is not installed on the device.
 */
data class TestAppApks(
    /**
     * The host APK, in which the instrumented tests are injected.
     * Usually, this is the app under test, but in our case the tests don't care about the host app, and just run
     * whatever app we want (the real app under test) via 'monkey'.
     *
     * We still need to ensure this APK is installed on the emulator, because it still needs to host the tests.
     */
    val hostApk: Path,
    /**
     * The test APK, containing the actual instrumented tests, and injected into the host APK.
     */
    val instrumentationApk: Path,
)
