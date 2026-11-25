/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.core

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.temporal.ChronoUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileAttributesView
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

@Tag("cli-test-group-core")
class CleanCommandTest : CliTestBase() {
    /**
     * A temp directory outside the working copy, for the test data that emulates old unused data.
     *
     * It has to be outside the working copy because background services that watch the working copy (like the IDE's
     * file watchers or Spotlight) read the files shortly after they're created or modified, which refreshes their last
     * access times and makes them look recently used for `clean --old`.
     */
    @TempDir
    private lateinit var oldDataRoot: Path

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

            assertFalse(
                sharedCachesDir.exists(),
                "'clean --all' should still remove the shared caches outside a project"
            )
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

    @Test
    fun `clean --older-than-days cannot be used without --old`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("jvm-hello-world"),
            "clean", "--older-than-days", "10",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("missing option --old")
    }

    @Test
    fun `clean --dry-run cannot be used without --old`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("jvm-hello-world"),
            "clean", "--dry-run",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("missing option --old")
    }

    @Test
    fun `clean --old with non-positive days is an error`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("jvm-hello-world"),
            "clean", "--old", "--older-than-days", "0",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("The number of days must be a positive number")
    }

    // Tests that check actual logic of the old data detection.
    @Nested
    inner class OldDataDetection {
        @BeforeEach
        fun setUp() {
            // To test the behavior of the old data detection, we need to be able to set creation and access times.
            assumeTimesCanBeSet()
        }

        private fun assumeTimesCanBeSet() {
            val probe = oldDataRoot.resolve("times-probe")
            probe.writeText("probe")
            val now = Clock.System.now()
            // Different file systems store time attributes with different precision.
            // E.g., macOS trims creation time when modified to seconds.
            val creationTime = FileTime.from((now - 5.days).toJavaInstant().truncatedTo(ChronoUnit.SECONDS))
            val accessTime = FileTime.from((now - 2.days).toJavaInstant().truncatedTo(ChronoUnit.SECONDS))

            val probeAttributes = probe.fileAttributesView<BasicFileAttributeView>()
            probeAttributes.setTimes(
                /* lastModifiedTime = */ null,
                /* lastAccessTime = */ accessTime,
                /* createTime = */ creationTime,
            )

            val actualCreationTime = probeAttributes.readAttributes().creationTime()
            val actualAccessTime = probeAttributes.readAttributes().lastAccessTime()
            assumeTrue(
                actualCreationTime == creationTime,
                "The creation time can't be set on this system: $actualCreationTime != $creationTime"
            )
            assumeTrue(
                actualAccessTime == accessTime,
                "The access time can't be set on this system: $actualAccessTime != $accessTime"
            )
        }

        @Test
        fun `clean --old removes old log directories but keeps the recent ones and the rest of the build output`() =
            runSlowTest {
                val projectDir = testProject("jvm-hello-world")
                val buildOutputDir = buildOutputDirWithOldLogs()

                runCli(
                    projectDir = projectDir,
                    "clean", "--old",
                    buildOutputRoot = buildOutputDir,
                )

                val logsRoot = buildOutputDir / "logs"
                assertFalse(
                    (logsRoot / OLD_LOGS_DIR_NAME).exists(),
                    "'clean --old' should remove the old logs",
                )
                assertTrue(
                    (logsRoot / RECENT_LOGS_DIR_NAME).exists(),
                    "'clean --old' should keep the recent logs",
                )
                assertTrue(
                    (buildOutputDir / "artifacts" / "some-artifact").exists(),
                    "'clean --old' should keep the rest of the build output",
                )
            }

        @Test
        fun `clean --old --shared removes old shared cache data but keeps the recent data`() = runSlowTest {
            val projectDir = testProject("jvm-hello-world")
            val sharedCachesDir = sharedCachesDirWithOldData()

            runCli(
                projectDir = projectDir,
                "clean", "--old", "--shared",
                configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
            )

            assertFalse(
                (sharedCachesDir / "telemetry" / OLD_TRACES_DIR_NAME).exists(),
                "'clean --old --shared' should remove the old telemetry traces",
            )
            assertTrue(
                (sharedCachesDir / "telemetry" / RECENT_TRACES_DIR_NAME).exists(),
                "'clean --old --shared' should keep the recent telemetry traces",
            )
            assertFalse(
                (sharedCachesDir / "cli" / OLD_DISTRIBUTION_DIR_NAME).exists(),
                "'clean --old --shared' should remove the old distributions",
            )
            assertTrue(
                (sharedCachesDir / "cli" / RECENT_DISTRIBUTION_DIR_NAME).exists(),
                "'clean --old --shared' should keep the recent distributions",
            )
            assertFalse(
                (sharedCachesDir / "download.cache" / OLD_ARCHIVE_NAME).exists(),
                "'clean --old --shared' should remove the old downloaded archives",
            )
            assertTrue(
                (sharedCachesDir / "download.cache" / RECENT_ARCHIVE_NAME).exists(),
                "'clean --old --shared' should keep the recent downloaded archives",
            )
            assertFalse(
                (sharedCachesDir / "extract.cache" / "$OLD_ARCHIVE_NAME.abcdef.d").exists(),
                "'clean --old --shared' should remove the old extracted archives",
            )
            assertTrue(
                (sharedCachesDir / "extract.cache" / "$RECENT_ARCHIVE_NAME.abcdef.d").exists(),
                "'clean --old --shared' should keep the recent extracted archives",
            )
            assertTrue(
                (sharedCachesDir / "fus" / "old-fus-data").exists(),
                "'clean --old --shared' should not remove the FUS caches",
            )
        }

        @Test
        fun `clean --old --all removes old logs and old shared cache data`() = runSlowTest {
            val projectDir = testProject("jvm-hello-world")
            val buildOutputDir = buildOutputDirWithOldLogs()
            val sharedCachesDir = sharedCachesDirWithOldData()

            runCli(
                projectDir = projectDir,
                "clean", "--old", "--all",
                buildOutputRoot = buildOutputDir,
                configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
            )

            assertFalse((buildOutputDir / "logs" / OLD_LOGS_DIR_NAME).exists())
            assertTrue((buildOutputDir / "logs" / RECENT_LOGS_DIR_NAME).exists())
            assertFalse((sharedCachesDir / "cli" / OLD_DISTRIBUTION_DIR_NAME).exists())
            assertTrue((sharedCachesDir / "cli" / RECENT_DISTRIBUTION_DIR_NAME).exists())
        }

        @Test
        fun `clean --old --dry-run removes nothing and lists what would be removed`() = runSlowTest {
            val projectDir = testProject("jvm-hello-world")
            val sharedCachesDir = sharedCachesDirWithOldData()

            val result = runCli(
                projectDir = projectDir,
                "clean", "--old", "--shared", "--dry-run",
                configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
            )

            assertTrue(
                (sharedCachesDir / "cli" / OLD_DISTRIBUTION_DIR_NAME).exists(),
                "'clean --old --dry-run' should not remove anything",
            )
            assertTrue(
                (sharedCachesDir / "telemetry" / OLD_TRACES_DIR_NAME).exists(),
                "'clean --old --dry-run' should not remove anything",
            )
            result.assertStdoutContains(OLD_DISTRIBUTION_DIR_NAME)
            result.assertStdoutContains(OLD_TRACES_DIR_NAME)
        }

        @Test
        fun `clean --old with a threshold older than the data keeps the data`() = runSlowTest {
            val projectDir = testProject("jvm-hello-world")
            val sharedCachesDir = sharedCachesDirWithOldData()

            val result = runCli(
                projectDir = projectDir,
                "clean", "--old", "--shared", "--older-than-days", "100",
                configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
            )

            assertTrue(
                (sharedCachesDir / "cli" / OLD_DISTRIBUTION_DIR_NAME).exists(),
                "'clean --old' with a threshold older than the data should keep the old distributions",
            )
            assertTrue(
                (sharedCachesDir / "telemetry" / OLD_TRACES_DIR_NAME).exists(),
                "'clean --old' with a threshold older than the data should keep the old telemetry traces",
            )
            result.assertStdoutContains("No data older than 100 days was found")
        }

        @Test
        fun `clean --old judges logs and telemetry by their creation time, not by their last use time`() =
            runSlowTest {
                val projectDir = testProject("jvm-hello-world")
                val buildOutputDir = buildOutputDirWithLogsCreatedLongAgoButUsedSince()
                val sharedCachesDir = sharedCachesDirWithTelemetryCreatedLongAgoButUsedSince()

                runCli(
                    projectDir = projectDir,
                    "clean", "--old", "--all",
                    buildOutputRoot = buildOutputDir,
                    configureEnvironment = { putSharedCachesDir(sharedCachesDir) },
                )

                assertFalse(
                    (buildOutputDir / "logs" / OLD_BUT_USED_LOGS_DIR_NAME).exists(),
                    "'clean --old' should remove the logs created before the threshold, even if they were used after it",
                )
                assertFalse(
                    (sharedCachesDir / "telemetry" / OLD_BUT_USED_TRACES_DIR_NAME).exists(),
                    "'clean --old' should remove the telemetry traces created before the threshold, even if they were used after it",
                )
            }

        private fun buildOutputDirWithOldLogs(): Path {
            val dir = oldDataRoot / "build-output-old-logs"
            val oldLogsDir = dir / "logs" / OLD_LOGS_DIR_NAME
            (oldLogsDir / "info.log").createParentDirectories().writeText("old log content")
            oldLogsDir.ageDays(OLD_TEST_DATA_DAYS)

            val recentLogsDir = dir / "logs" / RECENT_LOGS_DIR_NAME
            (recentLogsDir / "info.log").createParentDirectories().writeText("recent log content")
            (dir / "artifacts").createDirectories()
            (dir / "artifacts" / "some-artifact").writeText("build output")
            return dir
        }

        private fun sharedCachesDirWithOldData(): Path {
            val dir = oldDataRoot / "shared-caches-old"

            // Telemetry
            val telemetryFolder = dir / "telemetry"
            val oldTracesDir = telemetryFolder / OLD_TRACES_DIR_NAME
            (oldTracesDir / "kotlin_cli_traces.jsonl").createParentDirectories().writeText("{}")
            oldTracesDir.ageDays(OLD_TEST_DATA_DAYS)
            val recentTracesDir = telemetryFolder / RECENT_TRACES_DIR_NAME
            (recentTracesDir / "kotlin_cli_traces.jsonl").createParentDirectories().writeText("{}")

            // Distributions
            val cliFolder = dir / "cli"
            val oldDistributionDir = cliFolder / OLD_DISTRIBUTION_DIR_NAME
            (oldDistributionDir / "lib" / "kotlin-cli.jar").createParentDirectories().writeText("old distribution")
            oldDistributionDir.ageDays(OLD_TEST_DATA_DAYS)
            val recentDistributionDir = cliFolder / RECENT_DISTRIBUTION_DIR_NAME
            (recentDistributionDir / "lib" / "kotlin-cli.jar").createParentDirectories()
                .writeText("recent distribution")

            // Download cache
            val downloadCache = dir / "download.cache"
            val oldArchive = downloadCache / OLD_ARCHIVE_NAME
            oldArchive.createParentDirectories().writeText("old archive")
            oldArchive.ageDays(OLD_TEST_DATA_DAYS)
            (downloadCache / RECENT_ARCHIVE_NAME).createParentDirectories().writeText("recent archive")

            // Extract cache
            val extractCache = dir / "extract.cache"
            val oldExtractDir = extractCache / "$OLD_ARCHIVE_NAME.abcdef.d"
            (oldExtractDir / "some-jdk-home").createParentDirectories().writeText("old extracted archive")
            oldExtractDir.ageDays(OLD_TEST_DATA_DAYS)
            val oldExtractFlag = (extractCache / "$OLD_ARCHIVE_NAME.abcdef.d.flag")
            oldExtractFlag.createParentDirectories().writeText("flag")
            oldExtractFlag.ageDays(OLD_TEST_DATA_DAYS)
            val recentExtractDir = extractCache / "$RECENT_ARCHIVE_NAME.abcdef.d"
            (recentExtractDir / "some-jdk-home").createParentDirectories().writeText("recent extracted archive")

            // Not in the scope of 'clean --old'
            (dir / "fus" / "old-fus-data").createDirectories().ageDays(OLD_TEST_DATA_DAYS)

            return dir
        }

        private fun buildOutputDirWithLogsCreatedLongAgoButUsedSince(): Path {
            val dir = oldDataRoot / "build-output-mixed-time-logs"
            val oldButUsedLogsDir = dir / "logs" / OLD_BUT_USED_LOGS_DIR_NAME
            (oldButUsedLogsDir / "info.log").createParentDirectories().writeText("old log content")
            // Last modified and access should match the current time
            oldButUsedLogsDir.ageDays(OLD_TEST_DATA_DAYS, setUsedTimes = false, setCreationTime = true)
            return dir
        }

        private fun sharedCachesDirWithTelemetryCreatedLongAgoButUsedSince(): Path {
            val dir = oldDataRoot / "shared-caches-mixed-time-telemetry"
            val telemetryFolder = dir / "telemetry"
            val oldButUsedTracesDir = telemetryFolder / OLD_BUT_USED_TRACES_DIR_NAME
            (oldButUsedTracesDir / "kotlin_cli_traces.jsonl").createParentDirectories().writeText("{}")
            // Last modified and access should match the current time
            oldButUsedTracesDir.ageDays(OLD_TEST_DATA_DAYS, setUsedTimes = false, setCreationTime = true)
            return dir
        }

        private fun Path.ageDays(
            days: Long,
            setUsedTimes: Boolean = true,
            setCreationTime: Boolean = true,
        ) {
            val time = FileTime.from((Clock.System.now() - days.days).toJavaInstant())
            fileAttributesView<BasicFileAttributeView>().setTimes(
                /* lastModifiedTime = */ if (setUsedTimes) time else null,
                /* lastAccessTime = */ if (setUsedTimes) time else null,
                /* createTime = */ if (setCreationTime) time else null,
            )
            if (isDirectory()) {
                listDirectoryEntries().forEach { it.ageDays(days, setUsedTimes, setCreationTime) }
            }
        }
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

    private companion object {

        /** Older than the default 'clean --old' threshold, which is 30 days. */
        const val OLD_TEST_DATA_DAYS = 40L
        const val OLD_LOGS_DIR_NAME = "amper_2026-01-01_10-00-00_1234-5678_build"

        const val RECENT_LOGS_DIR_NAME = "amper_2026-11-20_10-00-00_5678-9012_build"
        const val OLD_TRACES_DIR_NAME = "2026-01-01_10-00-00_1234_amper"

        const val RECENT_TRACES_DIR_NAME = "2026-11-20_10-00-00_5678_amper"
        const val OLD_DISTRIBUTION_DIR_NAME = "kotlin-cli-0.1.0"

        const val RECENT_DISTRIBUTION_DIR_NAME = "kotlin-cli-0.2.0"
        const val OLD_ARCHIVE_NAME = "1234567890-old-archive.zip"
        const val RECENT_ARCHIVE_NAME = "0987654321-recent-archive.zip"
        const val OLD_BUT_USED_LOGS_DIR_NAME = "amper_2026-01-01_11-00-00_2345-6789_build"
        const val OLD_BUT_USED_TRACES_DIR_NAME = "2026-01-01_11-00-00_2345_amper"
    }
}
