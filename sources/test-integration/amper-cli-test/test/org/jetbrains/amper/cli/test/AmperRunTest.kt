/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertLogStartsWith
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.test.LinuxOnly
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.WindowsOnly
import org.jetbrains.amper.test.spans.kotlinJvmCompilationSpans
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Disabled
import org.slf4j.event.Level
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class AmperRunTest : AmperCliTestBase() {

    private val specialCmdChars = "&()[]{}^=;!'+,`~"
    private val argumentsWithSpecialChars = listOf(
        "simple123",
        "my arg2",
        "my arg3 :\"'<>\$ && || ; \"\" $specialCmdChars ${specialCmdChars.asSequence().joinToString(" ")}",
    )

    @Test
    fun `run command help prints dash dash`() = runSlowTest {
        val r = runCli(projectDir = testProject("jvm-kotlin-test-smoke"), "run", "--help")

        // Check that '--' is printed before program arguments
        val string = "Usage: kotlin run [<options>] -- [<app_arguments>]..."

        assertTrue("There should be '$string' in `run --help` output") {
            r.stdout.lines().any { it == string }
        }
    }

    @Test
    fun `mixed java kotlin`() = runSlowTest {
        val result = runCli(projectDir = testProject("java-kotlin-mixed"), "run")
        result.assertLogStartsWith("Process exited with exit code 0", Level.INFO)
        result.assertStdoutContains("Output: <XYZ>")
    }

    @Test
    fun `jvm hello world with custom JDK 26`() = runSlowTest {
        val result = runCli(projectDir = testProject("jvm-custom-jdk"), "run")
        result.assertLogStartsWith("Process exited with exit code 0", Level.INFO)
        result.assertStdoutContains("Hello")
    }

    @Test
    fun `simple multiplatform cli on jvm`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "run", "--module=jvm-cli", "--", *argumentsWithSpecialChars.toTypedArray(),
        )

        val expectedOutput = """Hello Multiplatform CLI 12: JVM World
ARG0: <${argumentsWithSpecialChars[0]}>
ARG1: <${argumentsWithSpecialChars[1]}>
ARG2: <${argumentsWithSpecialChars[2]}>"""
        result.assertStdoutContains(expectedOutput)
    }

    @Test
    @MacOnly
    fun `simple multiplatform cli on mac`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "run", "--module=macos-cli", "--", *argumentsWithSpecialChars.toTypedArray(),
        )

        val expectedOutput = """Hello Multiplatform CLI 12: Mac World
ARG0: <${argumentsWithSpecialChars[0]}>
ARG1: <${argumentsWithSpecialChars[1]}>
ARG2: <${argumentsWithSpecialChars[2]}>"""
        result.assertStdoutContains(expectedOutput)
    }

    @Test
    @LinuxOnly
    fun `simple multiplatform cli on linux`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "run", "--module=linux-cli", "--platform=linuxX64", "--", *argumentsWithSpecialChars.toTypedArray(),
        )

        val expectedOutput = """Hello Multiplatform CLI 12: Linux World
ARG0: <${argumentsWithSpecialChars[0]}>
ARG1: <${argumentsWithSpecialChars[1]}>
ARG2: <${argumentsWithSpecialChars[2]}>"""
        result.assertStdoutContains(expectedOutput)
    }

    @Test
    @WindowsOnly
    fun `simple multiplatform cli on windows`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("simple-multiplatform-cli"),
            "run", "--module=windows-cli", "--", *argumentsWithSpecialChars.toTypedArray(),
        )

        val expectedOutput = """Hello Multiplatform CLI 12: Windows (Mingw) World
ARG0: <${argumentsWithSpecialChars[0]}>
ARG1: <${argumentsWithSpecialChars[1]}>
ARG2: <${argumentsWithSpecialChars[2]}>"""
        result.assertStdoutContains(expectedOutput)
    }

    @Test
    fun `run with jvm resource from dependency`() = runSlowTest {
        val result = runCli(projectDir = testProject("jvm-resources"), "run")
        result.assertStdoutContains("String from resources: Stuff From Resources")
    }

    @Test
    fun `run with access to resource as dir`() = runSlowTest {
        // This project tests reading a directory entry from the resources.
        // NoSuchElementException means it failed.
        runCli(projectDir = testProject("jvm-read-resource-dir"), "run")
    }

    @Test
    fun `run spring boot`() = runSlowTest {
        // Spring-core relies on ClassLoader::getResources for component scanning (to find bean definitions in the jar).
        // It expects the jar to contain directory entries and not just files.
        // Spring's PathMatchingResourcePatternResolver::doFindAllClassPathResources represents packages as a resources
        // (ex. org/springframework/boot/). So directory entries need to be resources inside the jar.
        // If directory entries are missing, the symptom is that Spring can't load the context.
        runCli(projectDir = testProject("spring-boot"), "run")
    }

    @Test
    fun `run executable jar`() = runSlowTest {
        runCli(projectDir = testProject("spring-boot"), "run", "-v", "release")
    }

    @Test
    fun `run works with stdin for jvm`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-input"),
            "run", "--module", "jvm-app",
            stdin = ProcessInput.Text("Hello World!\nBye World."),
        )

        assertContains(r.stdout, "Input: 'Hello World!'")
    }

    @Test
    @MacOnly
    fun `run works with stdin for native`() = runSlowTest {
        val r = runCli(
            projectDir = testProject("multiplatform-input"),
            "run", "--module", "macos-app",
            stdin = ProcessInput.Text("Hello World!\nBye World."),
        )

        assertContains(r.stdout, "Input: 'Hello World!'")
    }

    @Test
    fun `jvm run with JVM arg`() = runSlowTest {
        val projectRoot = testProject("jvm-run-print-systemprop")
        val result1 = runCli(projectRoot, "run", "--jvm-args=-Dmy.system.prop=hello")
        result1.assertStdoutContains("my.system.prop=hello")

        val result2 = runCli(projectRoot, "run", "--jvm-args=\"-Dmy.system.prop=hello world\"")
        result2.assertStdoutContains("my.system.prop=hello world")

        val result3 = runCli(projectRoot, "run", "--jvm-args=-Dmy.system.prop=hello\\ world")
        result3.assertStdoutContains("my.system.prop=hello world")
    }

    @Test
    fun `jvm run uses current working dir by default`() = runSlowTest {
        val projectRoot = testProject("cli-run-print-workingdir")
        val result = runCli(projectRoot, "run", "--module=jvm-app")
        result.assertStdoutContains("workingDir=${result.projectDir}")
    }

    @Test
    fun `jvm run uses specified --working-dir`() = runSlowTest {
        val projectRoot = testProject("cli-run-print-workingdir")
        val currentHome = System.getProperty("user.home")
        val result = runCli(projectRoot, "run", "--module=jvm-app", "--working-dir=$currentHome")
        result.assertStdoutContains("workingDir=$currentHome")
    }

    @Test
    fun `native run uses current working dir by default`() = runSlowTest {
        val projectRoot = testProject("cli-run-print-workingdir")
        val platform = SystemInfo.CurrentHost.nativePlatformName()
        val result = runCli(projectRoot, "run", "--platform=$platform")
        result.assertStdoutContains("workingDir=${result.projectDir}")
    }

    @Test
    fun `native run uses specified --working-dir`() = runSlowTest {
        val projectRoot = testProject("cli-run-print-workingdir")
        val platform = SystemInfo.CurrentHost.nativePlatformName()
        val currentHome = System.getProperty("user.home")
        val result = runCli(projectRoot, "run", "--platform=$platform", "--working-dir=$currentHome")
        result.assertStdoutContains("workingDir=$currentHome")
    }

    private fun SystemInfo.nativePlatformName(): String = when (family) {
        OsFamily.FreeBSD,
        OsFamily.Solaris,
        OsFamily.Linux -> when (arch) {
            Arch.X64 -> "linuxX64"
            Arch.Arm64 -> "linuxArm64"
        }
        OsFamily.MacOs -> when (arch) {
            Arch.X64 -> "macosX64"
            Arch.Arm64 -> "macosArm64"
        }
        OsFamily.Windows -> "mingwX64"
    }

    @Test
    fun `do not call kotlinc again if sources were not changed`() = runSlowTest {
        val projectRoot = testProject("jvm-language-version-2.1")

        val result1 = runCli(projectDir = projectRoot, "run")
        result1.assertStdoutContains("Hello, world!")
        result1.readTelemetrySpans().kotlinJvmCompilationSpans.assertSingle()

        val result2 = runCli(projectDir = projectRoot, "run")
        result2.assertStdoutContains("Hello, world!")
        result2.readTelemetrySpans().kotlinJvmCompilationSpans.assertNone()
    }

    @Test
    fun `exit code is propagated for JVM`() = runSlowTest {
        val projectRoot = testProject("jvm-exit-code")

        val result = runCli(projectDir = projectRoot, "run", expectedExitCode = 5, assertEmptyStdErr = false)
        result.assertStderrContains("Process exited with exit code 5")
    }

    @Test
    @LinuxOnly
    fun `exit code is propagated for Linux`() = runSlowTest {
        val projectRoot = testProject("multiplatform-cli-exit-code")

        val result = runCli(
            projectDir = projectRoot,
            "run", "--module", "linux-cli", "--platform=linuxX64",
            expectedExitCode = 5, assertEmptyStdErr = false,
        )
        result.assertStderrContains("Process exited with exit code 5")
    }

    @Test
    @MacOnly
    fun `exit code is propagated for macOS`() = runSlowTest {
        val projectRoot = testProject("multiplatform-cli-exit-code")

        val result = runCli(
            projectDir = projectRoot,
            "run", "--module", "macos-cli",
            expectedExitCode = 5, assertEmptyStdErr = false,
        )
        result.assertStderrContains("Process exited with exit code 5")
    }

    @Test
    @WindowsOnly
    fun `exit code is propagated for Windows`() = runSlowTest {
        val projectRoot = testProject("multiplatform-cli-exit-code")

        val result = runCli(
            projectDir = projectRoot,
            "run", "--module", "windows-cli",
            expectedExitCode = 5, assertEmptyStdErr = false,
        )
        result.assertStderrContains("Process exited with exit code 5")
    }

    @Test
    fun `spring-boot-kotlin springboot enabled should work`() = runSlowTest {
        val projectRoot = testProject("spring-boot-kotlin")

        val result = runCli(projectDir = projectRoot, "run")

        result.assertStdoutContains("Started MainKt")
    }

    @Test
    fun `run succeeds without filters if there is only one runnable app`() = runSlowTest {
        val projectRoot = testProject("multi-module-one-app-per-host")

        val result = runCli(projectDir = projectRoot, "run")

        result.assertStdoutContains("Hello, world!")
    }

    @Test
    fun `run fails if no app modules matching the platform`() = runSlowTest {
        val projectRoot = testProject("multi-module")

        val result = runCli(
            projectDir = projectRoot,
            "run", "--platform", "iosArm64",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("There are no application modules in the project that support the 'iosArm64' platform")
    }

    @Test
    fun `run fails if no app modules at all (single module)`() = runSlowTest {
        val projectRoot = testProject("jvm-publish")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("""
            ERROR: Module 'jvm-publish' cannot be run with the 'run' command because it's a library module. Please use an application product type.
            See the documentation for more info:
            https://kotlin-toolchain.org/dev/user-guide/product-types
        """.trimIndent())
    }

    @Test
    fun `run fails if no app modules at all (multi-module)`() = runSlowTest {
        val projectRoot = testProject("multi-module-libs-only")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("There are no application modules in the project, nothing to run")
    }

    @Test
    fun `run fails if more than one module matches (without platform filter)`() = runSlowTest {
        val projectRoot = testProject("multi-module-multi-apps")
        val result2 = runCli(
            projectDir = projectRoot,
            "run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result2.assertStderrContains("""
            There are several matching application modules in the project. Please specify one with the '--module' option.
            
            Runnable application modules:
              js-app
              jvm-app-1
              jvm-app-2
              wasm-js-app
        """.trimIndent())
    }

    @Test
    fun `run fails if more than one module matches (with platform filter)`() = runSlowTest {
        val projectRoot = testProject("multi-module-multi-apps")
        val result1 = runCli(
            projectDir = projectRoot,
            "run", "--platform", "jvm",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result1.assertStderrContains("""
            There are several matching application modules in the project. Please specify one with the '--module' option.
            
            Runnable application modules supporting the 'jvm' platform:
              jvm-app-1
              jvm-app-2
        """.trimIndent()
        )
    }

    @Test
    fun `run fails if the platform cannot run on the current host`() = runSlowTest {
        assumeFalse(
            SystemInfo.CurrentHost.family.isLinux && SystemInfo.CurrentHost.arch == Arch.X64,
            "This test is disabled on Linux x64 because we want to test when the 'linuxX64' platform cannot run on the host"
        )

        val projectRoot = testProject("multi-module-one-app-per-host")

        val result1 = runCli(
            projectDir = projectRoot,
            "run", "--platform", "linuxX64",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result1.assertStderrContains("ERROR: Code compiled for the 'linuxX64' platform cannot be run from the current host")
    }

    @Test
    fun `run fails gracefully for JS app`() = runSlowTest {
        val projectRoot = testProject("js-app")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("Module 'js-app' of type 'js/app' cannot be run directly by the Kotlin Toolchain at the moment")
    }

    @Test
    fun `run fails gracefully for Wasm JS app`() = runSlowTest {
        val projectRoot = testProject("wasm-js-app")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("Module 'wasm-js-app' of type 'wasm-js/app' cannot be run directly by the Kotlin Toolchain at the moment")
    }

    @Test
    fun `run fails gracefully for Wasm WASI app`() = runSlowTest {
        val projectRoot = testProject("wasm-wasi-app")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("Module 'wasm-wasi-app' of type 'wasm-wasi/app' cannot be run directly by the Kotlin Toolchain at the moment")
    }

    @Disabled("Our regular tests don't support running on iOS simulators at the moment. " +
            "This will be possible once we run on GitHub Actions.")
    @MacOnly
    @Test
    fun `run chooses the correct iOS simulator target by default`() = runSlowTest {
        val projectRoot = testProject("ios/hello-and-exit")

        runCli(projectDir = projectRoot, "run") // just check that it runs without error
    }

    @MacOnly
    @Test
    fun `run fails if --device-id is not provided and only physical targets are present`() = runSlowTest {
        val projectRoot = testProject("ios/device-only")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("ERROR: Please select a physical device to run module 'device-only' with --device-id.")
    }

    @Test
    fun `run fails if --device-id is provided but no mobile app modules are present (single module)`() = runSlowTest {
        val projectRoot = testProject("java-kotlin-mixed")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            "--device-id=something",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains(
            "No platforms of module 'java-kotlin-mixed' support device selection with --device-id. " +
                    "Please remove the option.\n\n" +
                    "Current platforms: jvm"
        )
    }

    @Test
    fun `run fails if --device-id is provided but no mobile app modules are present (multi-module)`() = runSlowTest {
        val projectRoot = testProject("simple-multiplatform-cli")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            "--device-id=something",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains(
            "There are no Android or iOS application modules in the project, and only those support selecting a " +
                    "device or emulator explicitly. Please remove the '--device-id' option."
        )
    }

    @Test
    fun `run fails if --device-id is provided with a platform that doesn't support it`() = runSlowTest {
        val projectRoot = testProject("compose-multiplatform-room")

        val result = runCli(
            projectDir = projectRoot,
            "run",
            "--platform=jvm",
            "--device-id=something",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains(
            "There are no application modules in the project that support the 'jvm' platform and device selection " +
                    "with --device-id."
        )
    }
}
