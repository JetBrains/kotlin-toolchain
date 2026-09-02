/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.dependencies

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertErrors
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import kotlin.io.path.div
import kotlin.test.Test

class VersionCatalogTest : CliTestBase() {

    @Test
    fun testCatalogAtRoot() = runSlowTest {
        runCli(testProject("version-catalog-root"), "build")
        val result = runCli(testProject("version-catalog-root"), "show", "dependencies")
        result.assertStdoutContains("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    }

    @Test
    fun testCatalogUnderGradle() = runSlowTest {
        runCli(testProject("version-catalog-gradle"), "build")
        val result = runCli(testProject("version-catalog-root"), "show", "dependencies")
        result.assertStdoutContains("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    }

    @Test
    fun `ktor server jetty resolves to jakarta artifact`() = runSlowTest {
        runCli(testProject("ktor-jetty-jakarta"), "build")
        val result = runCli(testProject("ktor-jetty-jakarta"), "show", "dependencies")
        result.assertStdoutContains("io.ktor:ktor-server-jetty-jakarta")
    }

    @Test
    fun testBothCatalogs_gradleIsIgnored() = runSlowTest {
        val result = runCli(
            projectDir = testProject(name = "version-catalog-root-and-gradle"),
            "build",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertStderrContains("No catalog value for the key `libs.kotlinx.datetime`")
    }

    @Test
    fun `test invalid version catalog key in template produces no duplicate errors`() = runSlowTest {
        val projectDir = testProject("version-catalog-in-template")
        val result = runCli(
            projectDir = projectDir,
            "build",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        result.assertErrors(
            "${projectDir / "common.module-template.yaml"}:2:5: No catalog value for the key `libs.nonexistent`",
            "${projectDir / "common.module-template.yaml"}:7:21: No catalog value for the key `libs.ktor.compiler.plugin1`",
            "failed to read Kotlin project model, refer to the errors above",
        )
    }
}
