/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.native

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.kotlin.native.downloadAndExtractKotlinNative
import org.jetbrains.amper.test.MacOnly
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("cli-test-group-native")
class CommonizerTaskTest: CliTestBase() {

    @Test
    @MacOnly
    fun `commonize ios`() = runSlowTest {
        val commonizedRootDir = runCommonizerCommand(projectName = "kmp-mobile")

        val expectedPlatformSets = listOf(
            listOf("ios_arm64", "ios_simulator_arm64", "ios_x64")
        )
        assertCommonizedPlatformSets(expectedPlatformSets, commonizedRootDir)
    }

    @Test
    fun `commonize one windows and one linux`() = runSlowTest {
        val commonizedRootDir = runCommonizerCommand(projectName = "win-and-linuxX64")

        val expectedPlatformSets = listOf(
            listOf("linux_x64", "mingw_x64")
        )
        assertCommonizedPlatformSets(expectedPlatformSets, commonizedRootDir)
    }

    @Test
    @MacOnly
    fun `commonize ios and two linuxes`() = runSlowTest {
        val commonizedRootDir = runCommonizerCommand(projectName = "ios-and-two-linux")

        val expectedPlatformSets = listOf(
            listOf("ios_arm64", "ios_simulator_arm64", "ios_x64"), // for the 'ios' fragment
            listOf("linux_arm64", "linux_x64"),  // for the 'linux' fragment
            listOf("ios_arm64", "ios_simulator_arm64", "ios_x64", "linux_arm64", "linux_x64") // for the 'native' fragment
        )
        assertCommonizedPlatformSets(expectedPlatformSets, commonizedRootDir)
    }

    @Test
    fun `commonize one windows and two linuxes`() = runSlowTest {
        val commonizedRootDir = runCommonizerCommand(projectName = "win-and-two-linux")

        val expectedPlatformSets = listOf(
            listOf("linux_arm64", "linux_x64"),  // for the 'linux' fragment
            listOf("linux_arm64", "linux_x64", "mingw_x64") // for the 'native' fragment
        )
        assertCommonizedPlatformSets(expectedPlatformSets, commonizedRootDir)
    }

    private suspend fun runCommonizerCommand(projectName: String): Path {
        val userCacheDir = tempRoot / "user-cache"
        userCacheDir.createDirectories()

        // TODO we should not rely on this. These tests should be done from the point of view of users without internal
        //   knowledge of how things work. In this case, we should customize KONAN_DATA_DIR and look at the commonized
        //   directory in it, just using the kotlin-native-rules library (KTC-agnostic).
        //   This is related to AMPER-5319.
        val konanDist = Downloader.downloadAndExtractKotlinNative(DefaultVersions.kotlin, AmperUserCacheRoot(userCacheDir))
        assertNotNull(konanDist, "Konan compiler was not downloaded")

        val runResult = runCli(
            projectDir = testProject("commonizer/$projectName"),
            "--shared-cache-dir=$userCacheDir",
            "ide-integration", "commonize-native-distribution",
        )

        assertEquals(0, runResult.exitCode, "The commonizer task failed with exit code ${runResult.exitCode}")
        val commonizedRootDir = konanDist.commonizedRoot
        assertTrue(commonizedRootDir.exists(), "$commonizedRootDir directory does not exist")
        return commonizedRootDir
    }

    private fun assertCommonizedPlatformSets(expectedPlatformSets: List<List<String>>, commonizedRootDir: Path) {
        for (expectedPlatformSet in expectedPlatformSets) {
            val folder = "(${expectedPlatformSet.joinToString()})"
            assertTrue((commonizedRootDir / folder).exists(),
                "$folder was not generated in $commonizedRootDir. " +
                "All files inside:\n ${commonizedRootDir.listDirectoryEntries().joinToString(separator = "\n") { it.name }}"
            )
        }
    }
}
