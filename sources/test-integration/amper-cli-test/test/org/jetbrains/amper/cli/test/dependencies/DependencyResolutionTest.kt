/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.dependencies

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Test

class DependencyResolutionTest : CliTestBase() {

    @Test
    fun `jvm exported dependencies`() = runSlowTest {
        val result = runCli(testProject("jvm-exported-dependencies"), "run", "--module=cli")

        result.assertStdoutContains("From Root Module + OneTwo")
    }
}
