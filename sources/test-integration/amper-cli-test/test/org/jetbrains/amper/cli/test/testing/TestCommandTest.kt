/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.testing

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.WindowsOnly
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import kotlin.io.path.readText
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContains

@Tag("cli-test-group-testing")
class TestCommandTest : CliTestBase() {

    @Test
    fun `jvm-kotlin-test-smoke test`() = runSlowTest {
        val projectRoot = testProject("jvm-kotlin-test-smoke")
        val result = runCli(projectDir = projectRoot, "test")

        // not captured by default...
        result.assertStdoutContains("Hello from test method, JavaString")
        result.assertStdoutContains("[         1 tests successful      ]")
        result.assertStdoutContains("[         0 tests failed          ]")

        val xmlReport = result.buildDir.resolve("reports/jvm-kotlin-test-smoke/jvm/TEST-junit-jupiter.xml")
            .readText()
        assertContains(xmlReport, "<testcase name=\"smoke()\" classname=\"apkg.ATest\"")
    }

    @Test
    fun `jvm-failed-test`() = runSlowTest {
        val projectRoot = testProject("jvm-failed-test")
        val result = runCli(projectDir = projectRoot, "test", assertEmptyStdErr = false, expectedExitCode = 1)
        result.assertStderrContains("ERROR: JVM tests failed for module 'jvm-failed-test' with exit code 1 (see errors above)")
        result.assertStdoutContains("MethodSource [className = 'FailedTest', methodName = 'stringComparisonFailure', methodParameterTypes = '']")
        result.assertStdoutContains("MethodSource [className = 'FailedTest', methodName = 'booleanFailure', methodParameterTypes = '']")
        result.assertStdoutContains("=> Exception: org.opentest4j.AssertionFailedError: Strings are not equal ==> expected: <EXPECTED_VALUE> but was: <ACTUAL_VALUE>")
    }

    @Test
    fun `fails when there are no test sources`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("jvm-kotlin-test-no-test-sources"),
            "test",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("No tests were discovered in the project.")
    }

    // Differs from the previous test because there are test sources (but no tests defined in them)
    @Test
    fun `fails when no tests were discovered`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("jvm-kotlin-test-no-tests"),
            "test",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("No tests were discovered in the project.")
    }

    @Test
    fun `succeeds when all discovered tests are skipped`() = runSlowTest {
        runCli(
            projectDir = testProject("jvm-kotlin-test-all-skipped"),
            "test",
            expectedExitCode = 0,
        )
    }

    @Test
    fun `no tests discovered message lists applied filters`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("jvm-kotlin-test-no-tests"),
            "test",
            "--include-test=com.example.MyTest/Nested.myTest(java.lang.String,kotlin.Int)",
            "--include-test=com.example.OtherTest.otherTest",
            "--include-classes=com.example.Specific",
            "--include-classes=com.example.*Wildcard",
            "--exclude-classes=com.example.Excluded*",
            "--include-tag=slow & !flaky",
            "--exclude-tag=broken",
            "--platform=jvm",
            "--include-module=jvm-kotlin-test-no-tests",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("No tests were discovered.")
        result.assertStderrContains("The following filters were applied:")
        // The message is markdown, so the backticks are not visible in the rendered output
        listOf(
            "Test myTest with parameter types (java.lang.String, kotlin.Int) in nested class Nested of com.example.MyTest",
            "Test otherTest in com.example.OtherTest",
            "Tests in the class or suite com.example.Specific",
            "Tests in classes or suites matching com.example.*Wildcard",
            "Tests in classes or suites not matching com.example.Excluded*",
            "Tests which tags match slow & !flaky",
            "Tests which tags don't match broken",
            "Only platforms: jvm",
            "From modules: jvm-kotlin-test-no-tests",
        ).forEach { result.assertStderrContains(it) }
    }

    @Test
    fun `run tests only from test fragment`() = runSlowTest {
        val projectContext = testProject("jvm-test-classpath")
        val result = runCli(projectDir = projectContext, "test")

        // asserts that ATest.smoke is run, but SrcTest.smoke isn't
        result.assertStdoutContains("[         1 tests successful      ]")
        result.assertStdoutContains("[         0 tests failed          ]")

        val xmlReport = result.buildDir.resolve("reports/jvm-test-classpath/jvm/TEST-junit-jupiter.xml")
            .readText()
        assertContains(xmlReport, "<testcase name=\"smoke()\" classname=\"apkg.ATest\"")
    }

    @Test
    fun `successfully run shutdown hooks`() = runSlowTest {
        val projectContext = testProject("jvm-test-shutdown-hook")
        val result = runCli(projectDir = projectContext, "test")

        // asserts that ATest.smoke is run, but SrcTest.smoke isn't
        result.assertStdoutContains("[         1 tests successful      ]")
        result.assertStdoutContains("[         0 tests failed          ]")
        result.assertStdoutContains("Hello from shutdown hook")
    }

    @Test
    fun `test fragment dependencies`() = runSlowTest {
        val result = runCli(projectDir = testProject("jvm-test-fragment-dependencies"), "test")
        result.assertStdoutContains("FromExternalDependencies:OneTwo FromProject:MyUtil")
    }

    // this test is useful in case we change our JUnit runner for a custom one using Kotlin (causing classpath issues)
    @Test
    fun `test using reflection`() = runSlowTest {
        runCli(testProject("jvm-test-using-reflection"), "test", "--platform=jvm")
    }

    @Test
    fun `jvm test with JVM arg`() = runSlowTest {
        val testProject = testProject("jvm-kotlin-test-systemprop")
        runCli(testProject, "test", "--jvm-args=-Dmy.system.prop=hello")

        // should fail without the system prop
        runCli(
            projectDir = testProject,
            "test",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        // should fail with an incorrect value for the system prop
        runCli(
            projectDir = testProject,
            "test",
            "--jvm-args=-Dmy.system.prop=WRONG",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
    }

    @Test
    fun `jvm test custom engine`() = runSlowTest {
        val result = runCli(projectDir = testProject("jvm-test-custom-engine"), "test")

        // tests are discovered
        result.assertStdoutContains("my-test-1")
        result.assertStdoutContains("my-test-2")
    }

    // This ensures we're not tempted to force the XMLOutputFactoru inside our own test framework
    @Test
    fun `jvm test with custom XMLOutputFactory`() = runSlowTest {
        val projectRoot = testProject("jvm-test-custom-xml-factory")
        val result = runCli(projectDir = projectRoot, "test")

        val xmlReport = result.buildDir.resolve("reports/jvm-test-custom-xml-factory/jvm/TEST-junit-jupiter.xml")
            .readText()
        assertContains(xmlReport, "<testcase name=\"smoke()\" classname=\"apkg.ATest\"")
    }

    @Test
    @MacOnly
    fun `missing platform to test`() = runSlowTest {
        val projectContext = testProject("jvm-kotlin-test-no-tests")
        val result = runCli(
            projectDir = projectContext,
            "test", "--platform=iosSimulatorArm64",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("No test tasks were found for platforms: IOS_SIMULATOR_ARM64")
    }

    @Test
    @MacOnly
    fun `unsupported platform to test`() = runSlowTest {
        // We make sure this test runs on ARM64 because that's what the expected list of runnable platforms is based on,
        // Also, the vast majority of macOS machines are ARM64 now anyway (Intel is no longer supported)
        Assumptions.assumeTrue { SystemInfo.CurrentHost.arch == Arch.Arm64 }

        val projectContext = testProject("simple-multiplatform-cli")
        val result = runCli(
            projectDir = projectContext,
            "test", "--platform=mingwX64",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains(
            """
            Unable to run requested platform(s) on the current system.

            Requested unsupported platforms: mingwX64
            Runnable platforms on the current system: android iosArm64 iosSimulatorArm64 js jvm macosArm64 macosX64 tvosArm64 tvosSimulatorArm64 wasmJs wasmWasi watchosArm32 watchosArm64 watchosDeviceArm64 watchosSimulatorArm64
        """.trimIndent()
        )
    }

    @Test
    fun `native test no tests`() = runSlowTest {
        val projectContext = testProject("native-test-no-tests")
        val result = runCli(
            projectDir = projectContext,
            "test",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("No tests were discovered in the project.")
    }

    @Test
    @WindowsOnly
    @Ignore("AMPER-475")
    fun `native test app test`() = runSlowTest {
        runCli(projectDir = testProject("native-test-app-test"), "test")
        // TODO assert that some test was actually run
    }

    @Test
    @WindowsOnly
    fun `simple multiplatform cli lib test on windows`() = runSlowTest {
        val projectContext = testProject("simple-multiplatform-cli")
        val result = runCli(projectDir = projectContext, "test", "--include-module=shared", "--platform=mingwX64")

        result.assertStdoutContains("Passed doTest")
    }

    @Test
    @MacOnly
    fun `simple multiplatform cli lib test on mac`() = runSlowTest {
        val projectContext = testProject("simple-multiplatform-cli")
        val result = runCli(projectDir = projectContext, "test", "--include-module=shared", "--platform=macosArm64")

        result.assertStdoutContains("Passed doTest")
    }

    @Test
    @MacOnly
    fun `simple multiplatform cli app test on mac`() = runSlowTest {
        val projectContext = testProject("simple-multiplatform-cli")
        val result = runCli(projectDir = projectContext, "test", "--include-module=macos-cli")

        result.assertStdoutContains("Passed doTest")
    }

    @Test
    fun `ktor-kodein test`() = runSlowTest {
        val projectContext = testProject("ktor-kodein")
        val result = runCli(projectDir = projectContext, "test")

        result.assertStdoutContains("Started KodeinSimpleApplicationTest")
        result.assertStdoutContains("Passed testProvideFakeRandom")
        result.assertStdoutContains("Completed KodeinSimpleApplicationTest")
        result.assertStdoutContains("1 tests successful")
    }

    @Test
    fun `spring-boot-kotlin springboot enabled should work`() = runSlowTest {
        val projectRoot = testProject("spring-boot-kotlin")

        // TODO: attach mockito as agent explicitly
        val result = runCli(projectDir = projectRoot, "test", assertEmptyStdErr = false)

        result.assertStdoutContains("Started DemoApplicationTests")
        result.assertStdoutContains("Passed contextLoads")
        result.assertStdoutContains("Completed DemoApplicationTests")
        result.assertStdoutContains("1 tests successful")
    }

    @Test
    fun `jvm test with environment variables`() = runSlowTest {
        val result = runCli(testProject("jvm-kotlin-test-envvars"), "test")
        result.assertStdoutContains("1 tests successful")
    }

    @Test
    fun `simple multiplatform cli lib test wasmJs module`() = runSlowTest {
        val projectContext = testProject("simple-multiplatform-cli")
        val result = runCli(
            projectDir = projectContext,
            "test",
            "--include-module=shared",
            "--platform=wasmJs",
            assertEmptyStdErr = false,
        )

        result.assertStdoutContains("Passed doTest")
    }
}
