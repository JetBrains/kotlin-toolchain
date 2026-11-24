/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.core

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.golden.SystemDependentGoldenFormatter
import org.jetbrains.amper.test.golden.SystemLineSeparatorGoldenFormatter
import org.jetbrains.amper.test.golden.assertContentEqualsGoldenFile
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.test.Test

@Tag("cli-test-group-core")
@Tag("gold-file")
class ShowModulesCommandTest : CliTestBase() {

    @OptIn(SystemDependentGoldenFormatter::class)
    private fun AmperCliResult.checkGold(caseName: String) {
        assertContentEqualsGoldenFile(
            goldenFile = Path("testResources/showModules/$caseName.result.txt"),
            actualContent = stdoutClean,
            // TODO normalize golden file line endings to LF via gitattributes (stdoutClean is normalized to LF)
            goldenFormatter = SystemLineSeparatorGoldenFormatter,
        )
    }
    
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