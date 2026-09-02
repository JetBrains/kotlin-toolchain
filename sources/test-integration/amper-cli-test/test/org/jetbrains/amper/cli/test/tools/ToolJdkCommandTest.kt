/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.tools

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.runSlowTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolJdkCommandTest : CliTestBase() {

    @Test
    fun `tool jdk jstack runs`() = runSlowTest {
        val p = newEmptyProjectDir(setupWrappers = true)
        val result = runCli(p, "tool", "jdk", "jstack", ProcessHandle.current().pid().toString())

        val requiredSubstring = "Full thread dump"
        assertTrue("stdout should contain '$requiredSubstring':\n${result.stdout}") {
            result.stdout.contains(requiredSubstring)
        }
    }
}