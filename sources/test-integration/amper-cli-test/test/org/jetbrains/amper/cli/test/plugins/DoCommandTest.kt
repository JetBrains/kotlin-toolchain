/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.plugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertErrors
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DoCommandTest : CliTestBase() {
    @Test
    fun `do command - unique name`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-commands")
        val result = runCli(
            projectDir = projectDir,
            "do", "my-plugin:uploadPictures",
        )
        result.assertStdoutContains("Uploading pictures...")
    }

    @Test
    fun `do command - ambiguous name`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-commands")
        val result = runCli(
            projectDir = projectDir,
            "do", "uploadPictures",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains(
            "Ambiguous command name 'uploadPictures'. " +
                    "Multiple plugins provide a command with this name. " +
                    "Please use a qualified name: my-plugin:uploadPictures, other-plugin:uploadPictures"
        )
    }

    @Test
    fun `do command - qualified name`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-commands")
        val result = runCli(
            projectDir = projectDir,
            "do", "other-plugin:uploadPictures",
        )
        result.assertStdoutContains("Uploading other pictures...")
    }

    @Test
    fun `do command - multiple modules`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-commands")
        val result = runCli(
            projectDir = projectDir,
            "do", "my-plugin:uploadPictures",
        )
        // One for app, one for app2
        result.assertStdoutContains("Uploading pictures...")
        // Count occurrences of "Uploading pictures..."
        val occurrences = result.stdout.split("Uploading pictures...").size - 1
        assertEquals(2, occurrences, "Should run for both app and app2")
    }

    @Test
    fun `do command - module selection`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-commands")
        val result = runCli(
            projectDir = projectDir,
            "do", "-m", "app", "my-plugin:uploadPictures",
        )
        result.assertStdoutContains("Uploading pictures...")
        val occurrences = result.stdout.split("Uploading pictures...").size - 1
        assertEquals(1, occurrences, "Should only run for app")
    }

    @Test
    fun `do command - missing command`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-commands")
        val result = runCli(
            projectDir = projectDir,
            "do", "nonExistent",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains(
            "Unknown command 'nonExistent'. Run `show commands` to list available commands"
        )
    }

    @Test
    fun `do command - conflicting command names`() = runSlowTest {
        val projectDir = testProject("extensibility/custom-commands-invalid")
        val pluginYaml = projectDir.resolve("plugin/plugin.yaml")
        val result = runCli(
            projectDir = projectDir,
            "do", "dummy",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertErrors(
            """
                Multiple commands have the same name `dummy`
                ╰─ Conflicting command names:
                   ├─ ${pluginYaml}:7:11
                   ╰─ ${pluginYaml}:8:5
            """.trimIndent(),
            "failed to read Kotlin project model, refer to the errors above",
        )
    }
}
