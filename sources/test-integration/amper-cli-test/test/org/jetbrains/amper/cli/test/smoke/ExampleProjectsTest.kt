/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.smoke

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.cli.test.utils.withTelemetrySpans
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.LocalAmperPublication
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.assertEqualsWithDiff
import org.jetbrains.amper.test.spans.assertJavaCompilationSpan
import org.jetbrains.amper.test.spans.assertSingleKotlinJvmCompilationSpan
import org.jetbrains.amper.test.spans.kotlinJvmCompilationSpans
import org.jetbrains.amper.test.spans.kotlinNativeCompilationSpans
import org.jetbrains.amper.test.spans.withAmperModule
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

@Tag("cli-test-group-smoke")
class ExampleProjectsTest : CliTestBase() {

    private fun exampleProject(name: String): Path = copyProjectToTempDir(Dirs.examplesRoot.resolve(name)).also {
        LocalAmperPublication.setupWrappersIn(it)
    }

    companion object {

        private val knownWarnings = listOf(
            // This one is expected as we build the project for iosArm64 target which is not installable on a device without signing.
            // https://youtrack.jetbrains.com/issue/KTC-4753/iOS-warning-in-compose-multiplatform-example-project
            "`DEVELOPMENT_TEAM` build setting is not detected in the Xcode project. Adding `CODE_SIGNING_ALLOWED=NO` to disable signing. You can still sign the app manually later.",
            // This warning is legitimate for the Kotlin version of spring-petclinic, and is also reported in Gradle.
            "static fun deserialize(bytes: ByteArray?): Any?' is deprecated. Deprecated in Java.",
            // This one is expected due to a bug in Skia fork build for iOS simulator maintained by Skiko.
            // The given object file lacks minimal iOS version argument and is marked with the Xcode version used for building it.
            // The file itself contains only data and doesn't contain any symbols that actually link with the iOS platform.
            // The issue is reported to the Skiko team.
            // https://youtrack.jetbrains.com/issue/KTC-6096
            "(libicu.icudtl_dat.o)) was built for newer 'iOS-simulator' version (18.5) than being linked (18.0)",
        )

        // Gradle deprecations come from AGP and KGP inside the Gradle build we generate, so they are hidden from users
        // (see KTC-6084).
        private val knownGradleDeprecations = setOf(
            // From AGP and KGP, fixed in AGP 9.5.0-rc02+ and KGP 2.3.20+
            "The Configuration.setVisible(boolean) method has been deprecated. This is scheduled to be removed in Gradle 11.",
        )

        // Must match the prefix used for Gradle deprecations in gradleProgressEvent.kt
        private const val GRADLE_DEPRECATION_LOG_PREFIX = "Gradle deprecation: "

        // Linking and xcodebuild produce some warnings on stderr (said warnings are ignored specifically)
        private val knownProjectsWithNonEmptyStderr = setOf("compose-desktop")

        // We use directory names, not paths, so the preview of the tests in IDE and TC is more readable
        @JvmStatic
        private fun findExampleProjects(): List<String> =
            Dirs.examplesRoot.listDirectoryEntries().filter { it.isDirectory() }.map { it.name }
    }

    @ParameterizedTest
    @MethodSource("findExampleProjects")
    fun `example project can be built without errors or warnings`(projectName: String) = runSlowTest {
        val buildResult = runCli(
            projectDir = exampleProject(projectName),
            "build",
            assertEmptyStdErr = projectName !in knownProjectsWithNonEmptyStderr,
            configureAndroidHome = true, // no need to be granular by project here, we'll install them once
        )
        val warnings = buildResult.stdoutClean.lines().filter { "WARN" in it }
        val unexpectedWarnings = warnings.filterNot { knownWarnings.any { warning -> it.contains(warning) } }
        assertEqualsWithDiff(unexpectedWarnings, emptyList(), "Unexpected warnings in $projectName")

        val unexpectedDeprecations = buildResult.debugLogs
            .map { it.message.trim() }
            .filter { it.startsWith(GRADLE_DEPRECATION_LOG_PREFIX) }
            .map { it.removePrefix(GRADLE_DEPRECATION_LOG_PREFIX) }
            .filterNot { it in knownGradleDeprecations }
            .distinct()
        assertEqualsWithDiff(unexpectedDeprecations, emptyList(), "Unexpected Gradle deprecations in $projectName")
    }

    @Test
    @MacOnly
    fun `compose-multiplatform`() = runSlowTest {
        val buildResult = runCli(
            projectDir = exampleProject("compose-multiplatform"),
            "build",
            configureAndroidHome = true,
            assertEmptyStdErr = false,
        )
        buildResult.withTelemetrySpans {
            // main/test for Jvm + main/test * debug for Android.
            kotlinJvmCompilationSpans.withAmperModule("shared").assertTimes(4)

            // debug for Android (no test sources).
            kotlinJvmCompilationSpans.withAmperModule("android-app").assertTimes(1)

            // main for Jvm (no test sources).
            kotlinJvmCompilationSpans.withAmperModule("jvm-app").assertSingle()

            // (main klib + framework for Ios) * 2 ios targets (no test sources)
            kotlinNativeCompilationSpans.withAmperModule("ios-app").assertTimes(2 * 2)
        }
    }

    @Test
    fun `compose-android_signing`() = runSlowTest {
        val bundleAndroidTask = ":compose-android:bundleAndroid"
        val result = runCli(
            projectDir = exampleProject("compose-android"),
            "task",
            bundleAndroidTask,
            configureAndroidHome = true,
        )
        val apk = result.getTaskOutputPath(bundleAndroidTask) / "gradle-project-release.aab"
        JarFile(apk.toFile()).use {
            assertTrue(it.getEntry("META-INF/KEYALIAS.RSA").size > 0)
        }
    }

    @Test
    fun `compose-multiplatform_r8`() = runSlowTest {
        val result = runCli(
            projectDir = exampleProject("compose-multiplatform"),
            "task", ":android-app:bundleAndroid",
            configureAndroidHome = true,
        )
        assertTrue {
            // FIXME: R8 config is not placed in the actual build directory, but rather relative to the project dir.
            (result.projectDir / "build" / "temp" / "full-r8-config.txt").exists()
        }
    }

    @Test
    @MacOnly
    fun `compose-multiplatform_apple-tests`() = runSlowTest {
        runCli(exampleProject("compose-multiplatform"), "test", "-p", "iosSimulatorArm64")
    }

    @Test
    fun `compose-multiplatform_buildAndroidDebug`() = runSlowTest {
        runCli(
            projectDir = exampleProject("compose-multiplatform"),
            "task",
            ":android-app:buildAndroidDebug",
            configureAndroidHome = true,
        )
    }

    @Test
    fun `compose-desktop`() = runSlowTest {
        val projectRoot = exampleProject("compose-desktop")
        val result = runCli(projectRoot, "build")
        result.readTelemetrySpans().assertSingleKotlinJvmCompilationSpan {
            hasCompilerArgumentStartingWith("-Xplugin=")
        }
    }

    @Test
    fun jvm() = runSlowTest {
        val projectRoot = exampleProject("jvm")

        val result = runCli(projectRoot, "run")
        assertContains(result.stdout, "Hello, World!")

        result.withTelemetrySpans {
            // testing some default compiler arguments
            assertSingleKotlinJvmCompilationSpan {
                doesNotHaveCompilerArgument("-language-version")
                doesNotHaveCompilerArgument("-api-version")
                hasCompilerArgument("-Xjdk-release=25")
            }
            assertJavaCompilationSpan {
                hasCompilerArgument("--release", "25")
            }
        }

        with(runCli(projectRoot, "test")) {
            assertContains(stdout, "Test run finished")
            assertContains(stdout, "1 tests successful")
            assertContains(stdout, "0 tests failed")
        }
    }

    @Test
    fun `compose-android`() = runSlowTest {
        val projectRoot = exampleProject("compose-android")
        val result = runCli(
            projectDir = projectRoot,
            "build", "--variant=debug", "--variant=release",
            configureAndroidHome = true,
        )
        result.readTelemetrySpans().kotlinJvmCompilationSpans.assertTimes(2) // debug + release
    }

    @Test
    fun `spring-petclinic`() = runSlowTest {
        val projectRoot = exampleProject("spring-petclinic")

        // TODO: enable assertEmptyStdErr when AMPER-4256 is fixed
        with(runCli(projectRoot, "test", assertEmptyStdErr = false)) {
            assertContains(stdout, "Test run finished")
            assertContains(stdout, "tests successful")
            assertContains(stdout, "0 tests failed")
        }
    }

    @Test
    fun `room-multiplatform`() = runSlowTest {
        val projectRoot = exampleProject("room-multiplatform")
        runCli(
            projectDir = projectRoot,
            "build",
            configureAndroidHome = true,
        )
    }
}
