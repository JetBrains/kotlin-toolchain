/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.plugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertErrors
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

@Tag("cli-test-group-plugins")
class CustomChecksTest : CliTestBase() {

    @Test
    fun `check runs all checks in all modules`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check",
        )
        result.assertStdoutContains("Check A run in app-with-checks")
        result.assertStdoutContains("Check B run in app-with-checks")
        result.assertStdoutContains("Check tests run in app-with-checks")
        result.assertStdoutContains("Check tests run in app-without-checks")
        result.assertStdoutDoesNotContain("Check A run in app-without-checks")
        result.assertStdoutDoesNotContain("Check B run in app-without-checks")
    }

    @Test
    fun `check with specific check name runs only that check`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "checkA",
        )
        result.assertStdoutContains("Check A run in app-with-checks")
        result.assertStdoutDoesNotContain("Check B run")
        result.assertStdoutDoesNotContain("Check tests run")
    }

    @Test
    fun `check with multiple check names runs only those checks`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "checkA", "checkB",
        )
        result.assertStdoutContains("Check A run in app-with-checks")
        result.assertStdoutContains("Check B run in app-with-checks")
        result.assertStdoutDoesNotContain("Check tests run")
    }

    @Test
    fun `check with module filter runs checks only in that module`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "-m", "app-with-checks",
        )
        result.assertStdoutContains("Check A run in app-with-checks")
        result.assertStdoutContains("Check B run in app-with-checks")
        result.assertStdoutContains("Check tests run in app-with-checks")
        result.assertStdoutDoesNotContain("Check tests run in app-without-checks")
    }

    @Test
    fun `check with module filter for module without plugin`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "-m", "app-without-checks", "checkA",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        ).assertStderrContains("Unknown check 'checkA'. Run `show checks` to list available checks")
    }

    @Test
    fun `check with nonexistent check name fails`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "nonExistentCheck",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        ).assertStderrContains("Unknown check 'nonExistentCheck'. Run `show checks` to list available checks")
    }

    @Test
    fun `check skip removes check from execution (qualified name)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "--skip", "checker:checkA",
        )
        result.assertStdoutDoesNotContain("Check A run")
        result.assertStdoutContains("Check B run in app-with-checks")
        result.assertStdoutContains("Check tests run in app-with-checks")
        result.assertStdoutContains("Check tests run in app-without-checks")
    }

    @Test
    fun `check skip tests runs only custom checks`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "--skip", "tests",
        )
        result.assertStdoutContains("Check A run in app-with-checks")
        result.assertStdoutContains("Check B run in app-with-checks")
        result.assertStdoutDoesNotContain("Check tests run")
    }

    @Test
    fun `check with only tests runs builtin tests check`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "tests",
        )
        result.assertStdoutDoesNotContain("Check A run")
        result.assertStdoutDoesNotContain("Check B run")
        result.assertStdoutContains("Check tests run in app-with-checks")
        result.assertStdoutContains("Check tests run in app-without-checks")
    }

    @Test
    fun `check skip nonexistent check fails`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "--skip", "nonExistentCheck",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        ).assertStderrContains("Unknown check 'nonExistentCheck' in --skip. Run `show checks` to list available checks")
    }

    @Test
    fun `check with both positional args and skip fails`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "--skip", "checkA", "checkB",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        ).assertStderrContains("Cannot use both positional check names and --skip at the same time")
    }

    @Test
    fun `check skip everything results in no check tasks found`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "check", "-m", "app-without-checks", "--skip", "tests",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        ).assertStderrContains("No checks were found for the specified filters")
    }

    @Test
    fun `check with invalid performedBy task name reports diagnostic`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-checks-invalid-task")
        val pluginYaml = projectDir.resolve("checker-plugin/plugin.yaml")
        runCli(
            projectDir = projectDir,
            "check",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        ).assertErrors(
            "${pluginYaml}:10:5: Property `name` can't have its value referencing external parameters.",
            "${pluginYaml}:13:5: Property `name` can't have its value referencing external parameters.",
            "${pluginYaml}:6:18: Expected a task name, got `nonExistentTask` instead. Registered task names are `runCheckA`.",
            """
               Multiple checks have the same name `badCheck`
               ╰─ Conflicting check names defined at:
                  ├─ ${pluginYaml}:7:11
                  ╰─ ${pluginYaml}:16:11
            """.trimIndent(),
            "failed to read Kotlin project model, refer to the errors above",
        )
    }

    @Test
    fun `check with ambiguous name from different plugins fails`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/custom-checks-ambiguous-name"),
            "check", "codestyle",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        ).assertStderrContains(
            "Ambiguous check name 'codestyle'. " +
                    "Multiple plugins provide a check with this name. " +
                    "Please use a qualified name: alpha:codestyle, beta:codestyle"
        )
    }

    @Test
    fun `check with qualified name runs specific plugin check`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks-ambiguous-name"),
            "check", "alpha:codestyle",
        )
        result.assertStdoutContains("Alpha codestyle run in app")
        result.assertStdoutDoesNotContain("Beta codestyle run")
    }

    @Test
    fun `check skip with ambiguous name from different plugins fails`() = runSlowTest {
        runCli(
            projectDir = testProject("extensibility/custom-checks-ambiguous-name"),
            "check", "--skip", "codestyle",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        ).assertStderrContains(
            "Ambiguous check name 'codestyle' in --skip. " +
                    "Multiple plugins provide a check with this name. " +
                    "Please use a qualified name: alpha:codestyle, beta:codestyle"
        )
    }

    @Test
    fun `check skip with qualified name works`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks-ambiguous-name"),
            "check", "--skip", "alpha:codestyle", "--skip", "tests",
        )
        result.assertStdoutContains("Beta codestyle run in app")
        result.assertStdoutDoesNotContain("Alpha codestyle run")
    }
}
