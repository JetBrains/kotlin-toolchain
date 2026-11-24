/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.core

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("cli-test-group-core")
class CleanCommandTest : CliTestBase() {

    @Test
    fun `clean removes the project build output and not the shared caches`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")
        val buildOutputDir = buildOutputDirWithContent()
        val sharedCachesDir = sharedCachesDirWithContent()

        runCli(
            projectDir = projectDir,
            "clean",
            buildOutputRoot = buildOutputDir,
            configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
        )

        assertFalse(buildOutputDir.exists(), "The project build output should be removed by 'clean'")
        assertTrue(sharedCachesDir.exists(), "'clean' should not remove the shared caches by default")
    }

    @Test
    fun `clean --shared removes the shared caches`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")
        val buildOutputDir = buildOutputDirWithContent()
        val sharedCachesDir = sharedCachesDirWithContent()

        runCli(
            projectDir = projectDir,
            "clean", "--shared",
            buildOutputRoot = buildOutputDir,
            configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
        )

        assertFalse(sharedCachesDir.exists(), "'clean --shared' should remove the shared caches")
        assertTrue(buildOutputDir.exists(), "'clean --shared' should not remove the project build output")
    }

    @Test
    fun `clean --all removes the project build output and the shared caches`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")
        val buildOutputDir = buildOutputDirWithContent()
        val sharedCachesDir = sharedCachesDirWithContent()

        runCli(
            projectDir = projectDir,
            "clean", "--all",
            buildOutputRoot = buildOutputDir,
            configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
        )

        assertFalse(buildOutputDir.exists(), "'clean --all' should remove the project build output")
        assertFalse(sharedCachesDir.exists(), "'clean --all' should remove the shared caches")
    }

    @Test
    fun `clean outside a project is an error`() = runSlowTest {
        withNonProjectDir(setupWrappers = true) { projectDir ->
            val result = runCli(
                projectDir = projectDir,
                "clean",
                expectedExitCode = 1,
                assertEmptyStdErr = false,
            )

            result.assertStderrContains("No Kotlin project found")
        }
    }

    @Test
    fun `clean --all outside a project cleans the shared caches and suggests --shared`() = runSlowTest {
        withNonProjectDir(setupWrappers = true) { projectDir ->
            val sharedCachesDir = sharedCachesDirWithContent()

            val result = runCli(
                projectDir = projectDir,
                "clean", "--all",
                configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
            )

            assertFalse(sharedCachesDir.exists(), "'clean --all' should still remove the shared caches outside a project")
            result.assertStdoutContains("No Kotlin project found")
            result.assertStdoutContains("--shared")
        }
    }

    @Test
    fun `clean --shared cannot be combined with --all`() = runSlowTest {
        runCli(
            projectDir = testProject("jvm-hello-world"),
            "clean", "--shared", "--all",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
    }

    private fun buildOutputDirWithContent(): Path {
        val dir = tempRoot / "build-output"
        dir.createDirectories()
        (dir / "some-build-output").writeText("build output")
        return dir
    }

    private fun sharedCachesDirWithContent(): Path {
        val dir = tempRoot / "shared-caches"
        dir.createDirectories()
        (dir / "some-cache-file").writeText("cache data")
        return dir
    }

    private fun MutableMap<String, String>.putSharedCachesDir(sharedCachesDir: Path) {
        put("KOTLIN_SHARED_CACHE_DIR", sharedCachesDir.absolutePathString())
    }
}
