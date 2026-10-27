/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.infra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.CliTestBase.WrapperMode
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.LocalAmperPublication
import org.junit.jupiter.api.Tag
import java.nio.file.FileSystemException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * An arbitrary version that is different from the version of the wrappers set up by the tests, so we can check that
 * the wrappers were effectively replaced. The same version is used in all tests on purpose, so the corresponding
 * distribution only has to be downloaded once.
 */
private const val someOldVersion = "0.11.0-dev-3939"

@Tag("cli-test-group-infra")
class UpdateCommandTest : CliTestBase() {

    @Test
    fun `update command in project root using local wrapper updates project wrappers`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world", setupWrappers = true)

        val result = runCli(projectDir, "update", "--target-version=$someOldVersion", wrapperMode = WrapperMode.Local)

        assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
        projectDir.assertShellWrapperVersionEquals(someOldVersion)
        projectDir.assertWinWrapperVersionEquals(someOldVersion)
    }

    @Test
    fun `update command in project root using global wrapper updates project wrappers`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world", setupWrappers = true)

        val result = runCli(projectDir, "update", "--target-version=$someOldVersion", wrapperMode = WrapperMode.Global)

        assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
        projectDir.assertShellWrapperVersionEquals(someOldVersion)
        projectDir.assertWinWrapperVersionEquals(someOldVersion)
    }

    @Test
    fun `update command in project subdir updates project wrappers`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world", setupWrappers = true)
        val subDir = (projectDir / "src")

        // We need global mode because we're not in the project's root dir. We could otherwise path a custom path.
        val result = runCli(subDir, "update", "--target-version=$someOldVersion", wrapperMode = WrapperMode.Global)

        assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
        projectDir.assertShellWrapperVersionEquals(someOldVersion)
        projectDir.assertWinWrapperVersionEquals(someOldVersion)

        assertTrue(
            subDir.relativeChildren().none { it == "kotlin" || it == "kotlin.bat" },
            "No wrapper should be created in the current directory",
        )
    }

    @Test
    fun `update command in project without wrappers prompts to create wrappers`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world", setupWrappers = false)

        // using GlobalIntrinsicVersion over Global to avoid the warning because the project has no wrapper
        val result = runCli(
            projectDir,
            "update",
            stdin = ProcessInput.text("y\n"),
            wrapperMode = WrapperMode.Global(useIntrinsicVersion = true),
        )

        assertTrue(result.stdout.contains("Would you like to create"), "The Kotlin CLI should ask for confirmation")
        assertTrue(projectDir.relativeChildren().containsAll(["kotlin", "kotlin.bat"]), "kotlin CLI wrapper scripts should be created")
    }

    @Test
    fun `update command out of project updates the calling wrapper and its siblings for other OSes`() = runSlowTest {
        val toolchainDir = fakeGlobalInstallDir()
        withNonProjectDir { dir ->
            val result = runCli(
                projectDir = dir,
                "update", "--target-version=$someOldVersion",
                // we don't use Global mode because we modify these wrappers, and want to check their version
                wrapperMode = WrapperMode.Global(customWrappersDir = toolchainDir),
            )
            assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
            toolchainDir.assertShellWrapperVersionEquals(someOldVersion)
            toolchainDir.assertWinWrapperVersionEquals(someOldVersion)

            assertEquals([], dir.relativeChildren(), "No wrapper should be created in the current directory")
        }
    }

    @Test
    fun `update command out of project doesn't create missing siblings`() = runSlowTest {
        val toolchainDir = fakeGlobalInstallDir()
        val otherOsScriptName = if (OsFamily.current.isWindows) "kotlin" else "kotlin.bat"
        (toolchainDir / otherOsScriptName).also { it.deleteExisting() }
        withNonProjectDir { dir ->
            val result = runCli(
                projectDir = dir,
                "update", "--target-version=$someOldVersion",
                // we don't use Global mode because we modify these wrappers, and want to check their version
                wrapperMode = WrapperMode.Global(customWrappersDir = toolchainDir),
            )
            assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
            if (OsFamily.current.isWindows) {
                toolchainDir.assertWinWrapperVersionEquals(someOldVersion)
            } else {
                toolchainDir.assertShellWrapperVersionEquals(someOldVersion)
            }

            assertEquals([], dir.relativeChildren(), "No wrapper should be created in the current directory")
            assertEquals([scriptNameForCurrentOs], toolchainDir.relativeChildren(), "Only the running wrapper should be present")
        }
    }

    @Test
    fun `update --self command with global wrapper updates the global wrapper and sibling`() = runSlowTest {
        val toolchainDir = fakeGlobalInstallDir()
        val projectDir = testProject("jvm-hello-world", setupWrappers = true)

        val result = runCli(
            projectDir = projectDir,
            "update", "--self", "--target-version=$someOldVersion",
            // we don't use Global mode because we modify these wrappers, and want to check their version
            wrapperMode = WrapperMode.Global(customWrappersDir = toolchainDir),
        )

        assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
        toolchainDir.assertShellWrapperVersionEquals(someOldVersion)
        toolchainDir.assertWinWrapperVersionEquals(someOldVersion)

        assertEquals(["kotlin", "kotlin.bat"], toolchainDir.relativeChildren(), "The wrapper scripts should still be there")
        assertEquals("1.0-SNAPSHOT", projectDir.readVersionInBashScript(), "Project-level kotlin wrapper should be unchanged")
        assertEquals("1.0-SNAPSHOT", projectDir.readVersionInBatchScript(), "Project-level kotlin.bat wrapper should be unchanged")
    }

    @Test
    fun `update --self command with global wrapper doesn't create project wrappers`() = runSlowTest {
        val toolchainDir = fakeGlobalInstallDir()
        val projectDir = testProject("jvm-hello-world", setupWrappers = false)

        val result = runCli(
            projectDir = projectDir,
            "update", "--self", "--target-version=$someOldVersion",
            // we don't use Global mode because we modify these wrappers, and want to check their version
            wrapperMode = WrapperMode.Global(customWrappersDir = toolchainDir),
        )

        assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
        toolchainDir.assertShellWrapperVersionEquals(someOldVersion)
        toolchainDir.assertWinWrapperVersionEquals(someOldVersion)

        assertEquals(["kotlin", "kotlin.bat"], toolchainDir.relativeChildren(), "The wrapper scripts should still be there")
        assertTrue(
            projectDir.relativeChildren().none { it == "kotlin" || it == "kotlin.bat" },
            "No wrapper should be created in the current directory",
        )
    }

    @Test
    fun `update --self command with global wrapper doesn't create the missing global wrapper variant when absent`() = runSlowTest {
        val toolchainDir = fakeGlobalInstallDir()
        val otherOsScriptName = if (OsFamily.current.isWindows) "kotlin" else "kotlin.bat"
        (toolchainDir / otherOsScriptName).also { it.deleteExisting() }
        val projectDir = testProject("jvm-hello-world", setupWrappers = true)
        val result = runCli(
            projectDir = projectDir,
            "update", "--self", "--target-version=$someOldVersion",
            // we don't use Global mode because we modify these wrappers, and want to check their version
            wrapperMode = WrapperMode.Global(customWrappersDir = toolchainDir),
        )
        assertTrue(result.stdout.contains("Update successful"), "Update should be successful")
        if (OsFamily.current.isWindows) {
            toolchainDir.assertWinWrapperVersionEquals(someOldVersion)
        } else {
            toolchainDir.assertShellWrapperVersionEquals(someOldVersion)
        }

        assertEquals([scriptNameForCurrentOs], toolchainDir.relativeChildren(), "Only the running wrapper should be present")
        assertEquals("1.0-SNAPSHOT", projectDir.readVersionInBashScript(), "Project-level kotlin wrapper should be unchanged")
        assertEquals("1.0-SNAPSHOT", projectDir.readVersionInBatchScript(), "Project-level kotlin.bat wrapper should be unchanged")
    }

    @Test
    fun `update --create command in project creates wrappers without confirmation`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world", setupWrappers = false)

        // using GlobalIntrinsicVersion over Global to avoid the warning because the project has no wrapper
        val result = runCli(
            projectDir = projectDir,
            "update", "--create",
            wrapperMode = WrapperMode.Global(useIntrinsicVersion = true),
        )

        assertFalse(result.stdout.contains("?"), "The Kotlin CLI should not ask for confirmation")
        assertTrue(projectDir.relativeChildren().containsAll(["kotlin", "kotlin.bat"]), "kotlin CLI wrapper scripts should be created")
    }

    @Test
    fun `update --create command out of project creates wrappers in the working dir without confirmation`() = runSlowTest {
        withNonProjectDir { dir ->
            val result = runCli(
                projectDir = dir,
                "update", "--create",
                wrapperMode = WrapperMode.Global(useIntrinsicVersion = true)
            )

            assertFalse(result.stdout.contains("?"), "The Kotlin CLI should not ask for confirmation")
            assertTrue(dir.relativeChildren().containsAll(["kotlin", "kotlin.bat"]), "kotlin CLI wrapper scripts should be created")
        }
    }

    @Test
    fun `update --self command cannot be used with --target-dir`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")

        val result = runCli(
            projectDir = projectDir,
            "update", "--target-dir=.", "--self",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("Error: option --self cannot be used with --target-dir")
    }

    @Test
    fun `update command replaces existing wrappers with latest release`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir)

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        assertNotEquals("1.0-SNAPSHOT", bashVersion, "kotlin bash script should have the new version")
        assertNotEquals("1.0-SNAPSHOT", batVersion, "kotlin bat script should have the new version")
        assertFalse(bashVersion.contains("-dev-"), "kotlin bash script should not get a dev version, got $bashVersion")
        assertFalse(batVersion.contains("-dev-"), "kotlin bat script should not get a dev version, got $batVersion")
    }

    @Test
    fun `update --dev command replaces existing wrappers with latest dev version`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir, "--dev")

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        // This is not technically correct: right after a release, the release version should be picked up
        // (it's the latest among all versions, release + dev)
        assertTrue(bashVersion.contains("-dev-"), "kotlin bash script new version should contain '-dev-', got $bashVersion")
        assertTrue(batVersion.contains("-dev-"), "kotlin bat script new version should contain '-dev-', got $batVersion")
    }

    @Test
    fun `update --target-version command replaces existing wrappers with specific version`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir, "--target-version=0.11.0-dev-3939")

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        assertEquals("0.11.0-dev-3939", bashVersion, "kotlin bash script should have the new version")
        assertEquals("0.11.0-dev-3939", batVersion, "kotlin bat script should have the new version")
    }

    @Test
    fun `can downgrade from current to 0_11_0`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir, "--target-version=0.11.0")

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        assertEquals("0.11.0", bashVersion, "kotlin bash script should have the new version")
        assertEquals("0.11.0", batVersion, "kotlin bat script should have the new version")
    }

    @Test
    fun `can update from 0_11_1 to current`() = runSlowTest {
        val projectDir = createBasicProjectWithWrappers(version = "0.11.1")
        assertCanUpdateToCurrent(projectDir)
    }

    private suspend fun createBasicProjectWithWrappers(version: String): Path {
        val projectDir = testProject("jvm-hello-world")
        // using the global wrapper so we don't have to await the batch wrapper update here
        runCli(projectDir, "update", "--target-version=$version", wrapperMode = WrapperMode.Global)
        return projectDir
    }

    @Test
    fun `can update from latest dev to current`() = runSlowTest {
        val projectDir = testProject("jvm-hello-world")
        // using the global wrapper so we don't have to await the batch wrapper update here
        runCli(projectDir, "update", "--dev", wrapperMode = WrapperMode.Global)

        assertCanUpdateToCurrent(projectDir)
    }

    private suspend fun assertCanUpdateToCurrent(projectDir: Path) {
        val (bashVersion, batVersion) = runAmperUpdateAndAwaitWinWrapper(
            projectDir,
            "--target-version=1.0-SNAPSHOT",
            "--repository=$localAmperDistRepoUrl",
        )
        assertEquals("1.0-SNAPSHOT", bashVersion, "kotlin bash script should have the new version")
        assertEquals("1.0-SNAPSHOT", batVersion, "kotlin bat script should have the new version")
    }

    private data class UpdateResult(
        val bashVersion: String,
        val batVersion: String,
        val commandResult: AmperCliResult,
    )

    /**
     * Runs the `./kotlin update` command on the wrappers of [projectDir] with the given [options], and waits for the
     * Windows wrapper to match the Linux one (in case the wrapper is updated asynchronously after the update command
     * exits).
     */
    private suspend fun runAmperUpdateAndAwaitWinWrapper(projectDir: Path, vararg options: String): UpdateResult {
        val result = runCli(projectDir = projectDir, "update", *options)

        val shWrapperVersion = projectDir.readVersionInBashScript()

        // On Windows, the bat script sometimes cannot be changed in-place, so we have to wait for the late replacement
        val batWrapperVersion = if (OsFamily.current.isWindows) {
            projectDir.awaitWinWrapperVersionPredicate(
                predicate = { batVersion -> batVersion == shWrapperVersion },
                onNeverReachedFailWith = { v ->
                    "The kotlin.bat wrapper version ($v) doesn't match the 'kotlin' (sh) version $shWrapperVersion after 20 attempts"
                }
            )
        } else {
            projectDir.readVersionInBatchScript()
        }
        return UpdateResult(
            bashVersion = checkNotNull(shWrapperVersion),
            batVersion = checkNotNull(batWrapperVersion),
            commandResult = result,
        )
    }

    /**
     * Creates a new directory containing the Kotlin wrapper scripts, simulating a Kotlin Toolchain installation
     * outside any project.
     *
     * Unlike the global wrappers used by [runCli], the wrappers created here are specific to the current test, so the
     * tests can safely update them.
     */
    private fun fakeGlobalInstallDir(): Path = (tempRoot / "toolchain").createDirectories().also {
        LocalAmperPublication.setupWrappersIn(it)
    }

    private fun Path.assertShellWrapperVersionEquals(expectedVersion: String) {
        assertEquals(expectedVersion, readVersionInBashScript(), "kotlin (sh) should exist with version $expectedVersion")
    }

    private suspend fun Path.assertWinWrapperVersionEquals(expectedVersion: String) {
        if (OsFamily.current.isWindows) {
            awaitWinWrapperVersionPredicate(
                predicate = { it == expectedVersion },
                onNeverReachedFailWith = { actualVersion ->
                    if (actualVersion == null) {
                        "kotlin.bat should exist with version $expectedVersion, but was not found"
                    } else {
                        "kotlin.bat should have version $expectedVersion, but was $actualVersion"
                    }
                }
            )
        } else {
            assertEquals(expectedVersion, readVersionInBatchScript(), "kotlin.bat should exist with version $expectedVersion")
        }
    }

    private suspend fun Path.awaitWinWrapperVersionPredicate(
        predicate: (String?) -> Boolean,
        onNeverReachedFailWith: (String?) -> String,
    ) : String? {
        // we switch context to use real time instead of the virtual time from runTest (test coroutine scheduler)
        return withContext(Dispatchers.IO) {
            repeat(20) {
                val version = readVersionInBatchScript()
                if (predicate(version)) {
                    return@withContext version
                }
                delay(100.milliseconds)
            }
            fail(onNeverReachedFailWith(readVersionInBatchScript()))
        }
    }

    private fun Path.readVersionInBashScript(): String? {
        val kotlin = resolve("kotlin")
        if (!kotlin.exists()) {
            return null
        }
        return kotlin.readAmperVersionVariable(versionAssignmentPrefix = "kotlin_cli_version=")
    }

    private suspend fun Path.readVersionInBatchScript(): String? {
        val batchWrapper = resolve("kotlin.bat")
        if (!batchWrapper.exists()) {
            return null
        }
        lateinit var exception: FileSystemException
        repeat(20) {
            try {
                return batchWrapper.readAmperVersionVariable(versionAssignmentPrefix = "set kotlin_cli_version=")
            } catch (e: FileSystemException) { // happens when the async process is still writing to the file
                exception = e
                delay(100.milliseconds)
            }
        }
        throw RuntimeException("Failed to read version from $batchWrapper after 20 attempts: $exception", exception)
    }

    private fun Path.readAmperVersionVariable(versionAssignmentPrefix: String): String {
        val scriptContents = readLines()
        return scriptContents
            .firstOrNull { it.startsWith(versionAssignmentPrefix) }
            ?.removePrefix(versionAssignmentPrefix)
            ?: fail("'$versionAssignmentPrefix' was not found in '${name}', actual content:\n${scriptContents.ifEmpty { "<empty>" }}")
    }

    private fun Path.relativeChildren(): List<String> =
        listDirectoryEntries().map { it.relativeTo(this).pathString }.sorted()
}
