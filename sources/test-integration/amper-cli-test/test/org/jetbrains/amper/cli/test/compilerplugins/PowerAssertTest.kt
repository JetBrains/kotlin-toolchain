/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.compilerplugins

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("cli-test-group-compilerplugins")
class PowerAssertTest : CliTestBase() {

    @Test
    fun `power-assert errors are only reported on assert by default`() = runSlowTest {
        val result = runCli(
            testProject(name = "kotlin-powerassert"),
            "test",
            "--include-module=default-functions",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStdoutContains("""
            assert(name1 == name2 + name1[2])
                   |     |  |     | |    |
                   |     |  |     | |    'o'
                   |     |  |     | "george"
                   |     |  |     "fredo"
                   |     |  "fred"
                   |     false
                   "george"
        """.trimIndent())

        // assertEquals should be processed by default
        result.assertStdoutContains("org.opentest4j.AssertionFailedError: expected: <george> but was: <ed>")
    }

    @Test
    fun `power-assert errors are properly reported on custom functions`() = runSlowTest {
        val result = runCli(
            testProject(name = "kotlin-powerassert"),
            "test",
            "--include-module=custom-functions",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStdoutContains("""
            assert(name1 == name2 + name1[2])
                   |     |  |     | |    |
                   |     |  |     | |    'o'
                   |     |  |     | "george"
                   |     |  |     "fredo"
                   |     |  "fred"
                   |     false
                   "george"
        """.trimIndent())

        // assertEquals should be processed because explicitly mentioned in the settings
        result.assertStdoutContains("""
            assertEquals(name1, name2.substring(2, name2.length))
                         |      |     |            |     |
                         |      |     "ed"         |     4
                         |      "fred"             "fred"
                         "george"
        """.trimIndent())
    }

    // This test is here to check the correct addition of the PowerAssert runtime library, which is apparently needed
    // for this specific behavior. See discussion:
    // https://youtrack.jetbrains.com/issue/KT-69036/Power-Assert-indent-multiline-values#focus=Comments-27-14010784.0-0
    @Test
    fun `power-assert errors handle multiline strings`() = runSlowTest {
        val result = runCli(
            testProject(name = "kotlin-powerassert"),
            "test",
            "--include-module=multiline-strings",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStdoutContains("""
            assert((prefixValue + str.substring(0, 8)).length == 0)
                    |           | |   |                |      |
                    "Hello:"    | |   |                14     false
                                | |   ""${'"'}
                                | |   This
                                | |    Is
                                | |   ""${'"'}
                                | ""${'"'}
                                | This
                                |  Is
                                |   A
                                |    Long
                                |   Multiple
                                |  Line
                                | String
                                | ""${'"'}
                                ""${'"'}
                                Hello:This
                                 Is
                                ""${'"'}
        """.trimIndent())
    }
}
