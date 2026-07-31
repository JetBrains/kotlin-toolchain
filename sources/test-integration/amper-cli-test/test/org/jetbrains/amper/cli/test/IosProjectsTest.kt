/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import io.opentelemetry.api.common.AttributeKey
import org.jetbrains.amper.cli.test.utils.UpdatedAttribute
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.konancSpans
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.cli.test.utils.withTelemetrySpans
import org.jetbrains.amper.cli.test.utils.xcodeProjectGenSpans
import org.jetbrains.amper.cli.test.utils.xcodeProjectManagementSpans
import org.jetbrains.amper.cli.test.utils.xcodebuildSpans
import org.jetbrains.amper.telemetry.getAttribute
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.LocalAmperPublication
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.spans.FilteredSpans
import org.junit.jupiter.api.Disabled
import java.nio.file.Path
import java.util.*
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.expect

@MacOnly
class IosProjectsTest : AmperCliTestBase() {

    @Test
    fun `framework for simple for iosSimulatorArm64`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/interop"),
            "task", ":interop:frameworkIosSimulatorArm64Debug",
            assertEmptyStdErr = false,
        )
        result.readTelemetrySpans().konancSpans.assertZeroExitCode(times = 2)
    }

    @Test
    fun `framework for simple for iosArm64`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/interop"),
            "task", ":interop:frameworkIosArm64Debug",
            assertEmptyStdErr = false,
        )
        result.readTelemetrySpans().konancSpans.assertZeroExitCode(times = 2)
    }

    @Test
    fun `build for simple for iosSimulatorArm64`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/interop"),
            "build", "-p", "iosSimulatorArm64",
            assertEmptyStdErr = false,
        )
        result.withTelemetrySpans {
            xcodeProjectGenSpans.assertNone()
            assertFalse { xcodeProjectManagementSpans.assertSingle().getAttribute(UpdatedAttribute) }
            xcodebuildSpans.assertZeroExitCode()
        }
    }

    @Test
    fun `build for simple for iosSimulatorArm64 (release)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/interop"),
            "build", "-p", "iosSimulatorArm64", "-v", "release",
            assertEmptyStdErr = false,
        )
        result.withTelemetrySpans {
            xcodeProjectGenSpans.assertNone()
            assertFalse { xcodeProjectManagementSpans.assertSingle().getAttribute(UpdatedAttribute) }
            xcodebuildSpans.assertZeroExitCode()
        }
    }

    @Test
    fun `build for outdated-xcode-proj updates the project`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/outdated-xcode-proj"),
            "build", "-p", "iosSimulatorArm64",
            assertEmptyStdErr = false,
        )
        result.withTelemetrySpans {
            xcodeProjectGenSpans.assertNone()
            assertTrue { xcodeProjectManagementSpans.assertSingle().getAttribute(UpdatedAttribute) }
            xcodebuildSpans.assertZeroExitCode()
        }
    }

    @Test
    fun `framework for compose for iosSimulatorArm64`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/compose"),
            "task", ":compose:frameworkIosSimulatorArm64Debug",
            assertEmptyStdErr = false,
        )
        result.readTelemetrySpans().konancSpans.assertZeroExitCode(times = 2)
    }

    @Test
    fun `framework for compose for iosArm64`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/compose"),
            "task", ":compose:frameworkIosArm64Debug",
            assertEmptyStdErr = false,
        )
        result.readTelemetrySpans().konancSpans.assertZeroExitCode(times = 2)
    }

    @Test
    fun `buildIosApp for compose app for iosSimulatorArm64`() = runSlowTest {
        val firstBuildResult = runCli(
            projectDir = testProject(name = "ios/compose"),
            "build", "-p", "iosSimulatorArm64",
            assertEmptyStdErr = false,
        )
        firstBuildResult.readTelemetrySpans().xcodeProjectGenSpans.assertSingle()

        val secondBuildResult = runCli(
            projectDir = firstBuildResult.projectDir, // new root in temp dir
            "build", "-p", "iosSimulatorArm64",
            assertEmptyStdErr = false,
        )
        secondBuildResult.withTelemetrySpans {
            xcodeProjectGenSpans.assertNone()
            assertFalse { xcodeProjectManagementSpans.assertSingle().getAttribute(UpdatedAttribute) }
        }
    }

    @Test
    fun `build for compose app for all ios archs (arm64 without signing)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/compose"),
            "build", // build all the platforms
            assertEmptyStdErr = false,
        )

        expect(1) {
            // for iosArm64
            ("`DEVELOPMENT_TEAM` build setting is not detected in the Xcode project. " +
                    "Adding `CODE_SIGNING_ALLOWED=NO` to disable signing. " +
                    "You can still sign the app manually later.").toRegex(RegexOption.LITERAL)
                .findAll(result.stdout).count()
        }
        result.withTelemetrySpans {
            xcodeProjectGenSpans.assertSingle()
            // Current spans are for [iosArm64, iosSimulatorArm64]
            xcodebuildSpans.assertTimes(2)
        }

        val runResult = runCli(
            projectDir = result.projectDir,
            // A fake device ID is fine because we'll fail before it's actually used anyway
            "run", "--platform=iosArm64", "--device-id=12345",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        runResult.assertStderrContains(
            "Running an unsigned app on a physical device (iosArm64) is not possible. " +
                    "Please select a development team in the Xcode project editor (Signing & Capabilities) " +
                    "or use a simulator platform instead."
        )
    }

    @Test
    fun `run kotlin tests in simulator`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("ios/simpleTests"),
            "test",
            assertEmptyStdErr = false,
        )
        result.assertStdoutContains("Completed SimpleTest")
    }

    @Test
    @Disabled("X64 should be removed in the future. Keeping this test for now as disabled just for the case.")
    fun `compose-multiplatform - build debug with xcodebuild`() = runSlowTest {
        val tempProjectDir = (tempRoot / UUID.randomUUID().toString() / "non-intel").createDirectories()
        (Dirs.amperTestProjectsRoot / "ios/non-intel").copyToRecursively(tempProjectDir, followLinks = false)
        LocalAmperPublication.setupWrappersIn(tempProjectDir)

        val result = runXcodebuild(
            "-project", (tempProjectDir / "module.xcodeproj").pathString,
            "-scheme", "app",
            "-configuration", "Debug",
            "-arch", "x86_64",
            "-sdk", "iphonesimulator",
        )
        assertNotEquals(illegal = 0, actual = result.exitCode)
        assertContains(
            result.stdout, """
            ERROR: Platform 'iosX64' is not found for iOS module 'non-intel'.
            The module has declared platforms: IOS_ARM64 IOS_SIMULATOR_ARM64.
            Please declare the required platform explicitly in the module's file.
        """.trimIndent()
        )
    }

    @Test
    fun `manage-xcode IDE task generates Xcode project`() = runSlowTest {
        suspend fun runManageXcode(projectDir: Path): AmperCliResult = runCli(
            projectDir = projectDir,
            "ide-integration", "manage-xcode",
            assertEmptyStdErr = false,
        )

        val firstResult = runManageXcode(testProject("ios/compose"))
        firstResult.readTelemetrySpans().xcodeProjectGenSpans.assertSingle()

        val secondResult = runManageXcode(firstResult.projectDir)
        secondResult.readTelemetrySpans().xcodeProjectGenSpans.assertNone()
    }
}

private fun FilteredSpans.assertZeroExitCode() = assertSingle().apply {
    assertEquals(0, getAttribute(AttributeKey.longKey("exit-code")))
}

private fun FilteredSpans.assertZeroExitCode(times: Int) = assertTimes(times).forEach {
    assertEquals(0, it.getAttribute(AttributeKey.longKey("exit-code")))
}
