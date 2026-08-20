/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.readTelemetrySpans
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.telemetry.getListAttribute
import org.jetbrains.amper.test.spans.SpansTestCollector
import org.jetbrains.amper.test.spans.spansNamed
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Since on the CI there is no X11 environment, an app cannot be run because the agent also launches a desktop devtools
 * app. The only what happens in this test is it verified that everything is wired correctly.
 *
 * Note: asserting that stderr is empty makes no sense in this case because when there is no X11 the agent writes to
 * stderr about it.
 */
class ComposeHotReloadTest : AmperCliTestBase() {

    @Test
    fun `compose hot reload run wires agent env vars and properties for jvm-app`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-hot-reload"),
            "run",
            "--compose-hot-reload-mode",
            assertEmptyStdErr = false,
        )

        result.readTelemetrySpans().assertHotReloadJavaExecSpan()
    }

    @Test
    fun `compose hot reload run wires agent env vars and properties for multiplatform-lib`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-hot-reload-lib"),
            "run",
            "-m",
            "compose-hot-reload-lib",
            "--main-class",
            "MainKt",
            "--compose-hot-reload-mode",
            assertEmptyStdErr = false,
        )

        result.readTelemetrySpans().assertHotReloadJavaExecSpan()
    }

    @Test
    fun `compose hot reload run wires agent env vars and properties for for jvm-lib`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-hot-reload-jvm-lib"),
            "run",
            "-m",
            "compose-hot-reload-jvm-lib",
            "--main-class",
            "MainKt",
            "--compose-hot-reload-mode",
            assertEmptyStdErr = false,
        )

        result.readTelemetrySpans().assertHotReloadJavaExecSpan()
    }

    @Test
    fun `compose hot reload on non-compose jvm app should fail`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("jvm-run-print-systemprop"),
            "run",
            "--compose-hot-reload-mode",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("Compose must be enabled to use Compose Hot Reload mode")
    }

    @Test
    fun `compose hot reload on android app should fail`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "run",
            "-m", "app-android",
            "--compose-hot-reload-mode",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("Module 'app-android' doesn't support Compose Hot Reload because it's not a JVM " +
                "application. Please remove the --compose-hot-reload-mode option.")
    }

    @Test
    fun `compose hot reload with platform android in multi-module should fail`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "run",
            "--platform=android",
            "--compose-hot-reload-mode",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains(
            "ERROR: Compose Hot Reload only supports the JVM platform and cannot work with 'android'. " +
                    "Please remove the '--compose-hot-reload-mode' or the '--platform' option.",
        )
    }

    @Disabled("Running the app during the test makes it unable to terminate on its own (the window must be closed by hand)")
    @Test
    fun `compose hot reload without module in multi-module picks desktop jvm app`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "run",
            "--compose-hot-reload-mode",
        )

        result.readTelemetrySpans().assertHotReloadJavaExecSpan()
    }

    private fun SpansTestCollector.assertHotReloadJavaExecSpan() {
        val javaExecSpan = spansNamed("java-exec").assertSingle()

        val jvmArgs = javaExecSpan.getListAttribute("jvm-args")
        assertContains(jvmArgs, "-Dcompose.reload.devToolsEnabled=true")
        assertTrue(jvmArgs.any { it.startsWith("-Dcompose.reload.orchestration.port=") })

        val javaAgent = jvmArgs.single { it.startsWith("-javaagent:") }
        assertContains(javaAgent, "hot-reload-agent")
    }

    // TODO: Write e2e hot-reload tests?
}
