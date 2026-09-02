/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.plugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import kotlin.test.Test

class ShowChecksCommandTest : CliTestBase() {

    @Test
    fun `show checks command prints all available checks`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "show", "checks",
        )
        result.assertStdoutContains("""
            ╭────────────┬──────────────────────────────────────╮
            │ Check name │ ID of the plugin providing the check │
            ├────────────┼──────────────────────────────────────┤
            │ tests      │ (builtin)                            │
            ├────────────┼──────────────────────────────────────┤
            │ checkA     │ checker                              │
            ├────────────┼──────────────────────────────────────┤
            │ checkB     │ checker                              │
            ╰────────────┴──────────────────────────────────────╯
        """.trimIndent())
    }

    @Test
    fun `show checks command prints all available checks (plain)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "show", "checks", "--format=plain"
        )
        result.assertStdoutContains("""
            tests
            checker:checkA
            checker:checkB
        """.trimIndent())
    }

    @Test
    fun `show checks command filters by module`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "show", "checks", "-m", "app-without-checks",
        )
        result.assertStdoutContains("""
            ╭────────────┬──────────────────────────────────────╮
            │ Check name │ ID of the plugin providing the check │
            ├────────────┼──────────────────────────────────────┤
            │ tests      │ (builtin)                            │
            ╰────────────┴──────────────────────────────────────╯
        """.trimIndent())
    }

    @Test
    fun `show checks command filters by plugin ID`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "show", "checks", "--plugin", "checker",
        )
        result.assertStdoutContains("""
            ╭────────────┬──────────────────────────────────────╮
            │ Check name │ ID of the plugin providing the check │
            ├────────────┼──────────────────────────────────────┤
            │ checkA     │ checker                              │
            ├────────────┼──────────────────────────────────────┤
            │ checkB     │ checker                              │
            ╰────────────┴──────────────────────────────────────╯
        """.trimIndent())
    }

    @Test
    fun `show checks command warns on unknown plugin ID`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "show", "checks", "--plugin", "unknown-plugin",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        result.assertStderrContains("ERROR: Plugin with the id 'unknown-plugin' is not registered in the project.")
    }

    @Test
    fun `show checks command supports multiple modules`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("extensibility/custom-checks"),
            "show", "checks", "-m", "app-with-checks", "-m", "app-without-checks",
        )
        result.assertStdoutContains("""
            ╭────────────┬──────────────────────────────────────╮
            │ Check name │ ID of the plugin providing the check │
            ├────────────┼──────────────────────────────────────┤
            │ tests      │ (builtin)                            │
            ├────────────┼──────────────────────────────────────┤
            │ checkA     │ checker                              │
            ├────────────┼──────────────────────────────────────┤
            │ checkB     │ checker                              │
            ╰────────────┴──────────────────────────────────────╯
        """.trimIndent())
    }
}
