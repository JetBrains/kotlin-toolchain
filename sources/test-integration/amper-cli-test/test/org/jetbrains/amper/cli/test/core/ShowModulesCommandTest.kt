/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.core

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.golden.GoldFileTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.test.Test

@Tag("cli-test-group-core")
class ShowModulesCommandTest : CliTestBase() {

    private fun AmperCliResult.checkGold(caseName: String) = GoldFileTest(
        caseName = caseName,
        base = Path("testResources/showModules"),
    ) { stdoutClean }.doTest()
    
    @Test
    fun `correct name for single module project`() = runSlowTest {
        val r = runCli(projectDir = testProject("java-kotlin-mixed"), "show", "modules")

        r.checkGold("single-module")
    }

    @Test
    fun `correct name for single module project with explicit project root`() = runSlowTest {
        val r = runCli(projectDir = testProject("java-kotlin-mixed"), "show", "modules", "--project-dir=.")

        // Same as in the case above
        r.checkGold("single-module")
    }
}