/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.testing

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.test.LinuxOnly
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("cli-test-group-testing")
class CheckCommandTest : CliTestBase() {

    @LinuxOnly
    @ParameterizedTest
    @ValueSource(strings = ["all", "tests"])
    fun `check runs only debug native tests`(check: String) = runSlowTest {
        assumeTrue(SystemInfo.CurrentHost.arch == Arch.X64)
        val project = testProject("simple-mp-test")
        val result = runCli(project, *if (check == "all") ["check"] else ["check", "tests"])
        result.assertStdoutContains("Passed doTest")
        assertTrue(result.getTaskOutputPath(":simple-mp-test:linkLinuxX64TestDebug").exists())
        assertFalse(result.getTaskOutputPath(":simple-mp-test:linkLinuxX64TestRelease").exists())
    }
}