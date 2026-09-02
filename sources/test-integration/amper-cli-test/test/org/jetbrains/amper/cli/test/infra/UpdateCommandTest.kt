/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.infra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.test.AmperCliResult
import java.nio.file.FileSystemException
import java.nio.file.Path
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

class UpdateCommandTest : CliTestBase() {

    @Test
    fun `update command without options creates wrappers with confirmation`() = runSlowTest {
        val projectDir = newEmptyProjectDir()

        val result = runCli(projectDir, "update", stdin = ProcessInput.text("y\n"), wrapperMode = WrapperMode.GlobalIntrinsicVersion)

        assertTrue(result.stdout.contains("Would you like to create"), "The Kotlin CLI should ask for confirmation")
        assertEquals(listOf("kotlin", "kotlin.bat"), projectDir.relativeChildren(), "kotlin CLI wrapper scripts should be created")
    }

    @Test
    fun `update --create command creates wrappers without confirmation`() = runSlowTest {
        val projectDir = newEmptyProjectDir()

        val result = runCli(projectDir, "update", "--create", wrapperMode = WrapperMode.GlobalIntrinsicVersion)

        assertFalse(result.stdout.contains("?"), "The Kotlin CLI should not ask for confirmation")
        assertEquals(listOf("kotlin", "kotlin.bat"), projectDir.relativeChildren(), "The Kotlin CLI wrapper scripts should be created")
    }

    @Test
    fun `update command without options replaces existing wrappers with latest release`() = runSlowTest {
        val projectDir = newEmptyProjectDir(setupWrappers = true)

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir)

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        assertNotEquals("1.0-SNAPSHOT", bashVersion, "kotlin bash script should have the new version")
        assertNotEquals("1.0-SNAPSHOT", batVersion, "kotlin bat script should have the new version")
        assertFalse(bashVersion.contains("-dev-"), "kotlin bash script should not get a dev version, got $bashVersion")
        assertFalse(batVersion.contains("-dev-"), "kotlin bat script should not get a dev version, got $batVersion")
    }

    @Test
    fun `update --dev command replaces existing wrappers with latest dev version`() = runSlowTest {
        val projectDir = newEmptyProjectDir(setupWrappers = true)

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir, "--dev")

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        // This is not technically correct: right after a release, the release version should be picked up
        // (it's the latest among all versions, release + dev)
        assertTrue(bashVersion.contains("-dev-"), "kotlin bash script new version should contain '-dev-', got $bashVersion")
        assertTrue(batVersion.contains("-dev-"), "kotlin bat script new version should contain '-dev-', got $batVersion")
    }

    @Test
    fun `update --target-version command replaces existing wrappers with specific version`() = runSlowTest {
        val projectDir = newEmptyProjectDir(setupWrappers = true)

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir, "--target-version=0.11.0-dev-3939")

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        assertEquals("0.11.0-dev-3939", bashVersion, "kotlin bash script should have the new version")
        assertEquals("0.11.0-dev-3939", batVersion, "kotlin bat script should have the new version")
    }

    @Test
    fun `can downgrade from current to 0_11_0`() = runSlowTest {
        val projectDir = newEmptyProjectDir(setupWrappers = true)

        val (bashVersion, batVersion, commandResult) = runAmperUpdateAndAwaitWinWrapper(projectDir, "--target-version=0.11.0")

        assertTrue(commandResult.stdout.contains("Update successful"), "Update should be successful")
        assertEquals("0.11.0", bashVersion, "kotlin bash script should have the new version")
        assertEquals("0.11.0", batVersion, "kotlin bat script should have the new version")
    }

    @Test
    fun `can update from 0_11_1 to current`() = runSlowTest {
        val projectDir = createEmptyProjectWithWrappers(version = "0.11.1")
        assertCanUpdateToCurrent(projectDir)
    }

    private suspend fun createEmptyProjectWithWrappers(version: String): Path {
        val projectDir = newEmptyProjectDir()
        runCli(projectDir, "update", "--target-version=$version", "--create", wrapperMode = WrapperMode.GlobalIntrinsicVersion)
        return projectDir
    }

    @Test
    fun `can update from latest dev to current`() = runSlowTest {
        val projectDir = newEmptyProjectDir()
        runCli(projectDir, "update", "--dev", "--create", wrapperMode = WrapperMode.GlobalIntrinsicVersion)

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
     * Runs the `./kotlin update` command with the given [options] and waits for the Windows wrapper to match the linux
     * one (in case the wrapper is updated asynchronously after the update command exits).
     */
    private suspend fun runAmperUpdateAndAwaitWinWrapper(projectDir: Path, vararg options: String): UpdateResult {
        val result = runCli(
            projectDir = projectDir,
            "update", *options,
        )
        assertEquals(listOf("kotlin", "kotlin.bat"), projectDir.relativeChildren(), "kotlin CLI wrapper scripts should still be there")

        // On Windows, the bat script sometimes cannot be changed in-place, so we have to wait for the late replacement
        if (OsFamily.current.isWindows) {
            awaitWrapperVersionsMatchIn(projectDir)
        }
        return UpdateResult(
            bashVersion = projectDir.readVersionInBashScript(),
            batVersion = projectDir.readVersionInBatchScript(),
            commandResult = result,
        )
    }

    private suspend fun awaitWrapperVersionsMatchIn(projectDir: Path) {
        // we switch context to use real time instead of the virtual time from runTest (test coroutine scheduler)
        withContext(Dispatchers.IO) {
            repeat(20) {
                if (projectDir.readVersionInBashScript() == projectDir.readVersionInBatchScript()) {
                    return@withContext
                }
                delay(100.milliseconds)
            }
            fail(
                "Batch script version doesn't match bash script version in $projectDir after 20 attempts.\n" +
                        "Version in 'kotlin':     ${projectDir.readVersionInBashScript()}\n" +
                        "Version in 'kotlin.bat': ${projectDir.readVersionInBatchScript()}"
            )
        }
    }

    private fun Path.readVersionInBashScript(): String =
        resolve("kotlin").readAmperVersionVariable(versionVariablePrefix = "kotlin_cli_version=")

    private suspend fun Path.readVersionInBatchScript(): String {
        val batchWrapper = resolve("kotlin.bat")
        lateinit var exception: FileSystemException
        repeat(20) {
            try {
                return batchWrapper.readAmperVersionVariable(versionVariablePrefix = "set kotlin_cli_version=")
            } catch (e: FileSystemException) { // happens when the async process is still writing to the file
                exception = e
                delay(300.milliseconds)
            }
        }
        throw RuntimeException("Failed to read version from $batchWrapper after 20 attempts: $exception", exception)
    }

    private fun Path.readAmperVersionVariable(versionVariablePrefix: String): String {
        val scriptContents = readLines()
        return scriptContents
            .firstOrNull { it.startsWith(versionVariablePrefix) }
            ?.removePrefix(versionVariablePrefix)
            ?: fail("'$versionVariablePrefix' was not found in '${name}', actual content:\n${scriptContents.ifEmpty { "<empty>" }}")
    }

    private fun Path.relativeChildren(): List<String> =
        listDirectoryEntries().map { it.relativeTo(this).pathString }.sorted()
}
