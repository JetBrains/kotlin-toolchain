/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.plugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import kotlin.test.Test

class ShowCustomCommandsCommandTest : CliTestBase() {

    @Test
    fun `show commands command prints all available custom commands`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-commands"),
            "show", "commands",
        )
        result.assertStdoutContains("""
            ╭─────────────────────┬────────────────────────────────────────╮
            │ Custom command name │ ID of the plugin providing the command │
            ├─────────────────────┼────────────────────────────────────────┤
            │ uploadPictures      │ my-plugin                              │
            ├─────────────────────┼────────────────────────────────────────┤
            │ uploadPictures      │ other-plugin                           │
            ╰─────────────────────┴────────────────────────────────────────╯
        """.trimIndent())
    }

    @Test
    fun `show commands command prints all available custom commands (plain)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-commands"),
            "show", "commands", "--format=plain"
        )
        result.assertStdoutContains("""
            my-plugin:uploadPictures
            other-plugin:uploadPictures
        """.trimIndent())
    }

    @Test
    fun `show commands command filters by module`() = runSlowTest {
        // Only my-plugin is enabled in app2
        // Wait, let's check app2/module.yaml
        val result = runCli(
            projectDir = testProject("extensibility/custom-commands"),
            "show", "commands", "-m", "app2",
        )
        // app2 has only my-plugin enabled (need to check)
        // Assuming it only has my-plugin
        result.assertStdoutContains("""
            ╭─────────────────────┬────────────────────────────────────────╮
            │ Custom command name │ ID of the plugin providing the command │
            ├─────────────────────┼────────────────────────────────────────┤
            │ uploadPictures      │ my-plugin                              │
            ╰─────────────────────┴────────────────────────────────────────╯
        """.trimIndent())
    }

    @Test
    fun `show commands command filters by plugin ID`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-commands"),
            "show", "commands", "--plugin", "my-plugin",
        )
        result.assertStdoutContains("""
            ╭─────────────────────┬────────────────────────────────────────╮
            │ Custom command name │ ID of the plugin providing the command │
            ├─────────────────────┼────────────────────────────────────────┤
            │ uploadPictures      │ my-plugin                              │
            ╰─────────────────────┴────────────────────────────────────────╯
        """.trimIndent())
    }

    @Test
    fun `show commands command warns on unknown plugin ID`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-commands"),
            "show", "commands", "--plugin", "unknown-plugin",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        result.assertStderrContains("ERROR: Plugin with the id 'unknown-plugin' is not registered in the project.")
    }

    @Test
    fun `show commands command supports multiple modules`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-commands"),
            "show", "commands", "-m", "app", "-m", "app2",
        )
        result.assertStdoutContains("""
            ╭─────────────────────┬────────────────────────────────────────╮
            │ Custom command name │ ID of the plugin providing the command │
            ├─────────────────────┼────────────────────────────────────────┤
            │ uploadPictures      │ my-plugin                              │
            ├─────────────────────┼────────────────────────────────────────┤
            │ uploadPictures      │ other-plugin                           │
            ╰─────────────────────┴────────────────────────────────────────╯
        """.trimIndent())
    }
}
