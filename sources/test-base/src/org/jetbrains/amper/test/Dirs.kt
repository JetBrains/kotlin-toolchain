/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import org.jetbrains.amper.dependency.resolution.LocalM2RepositoryFinder
import org.jetbrains.amper.test.Dirs.persistentCaches
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Clock

object Dirs {

    /**
     * The root directory of the Amper project, which is the checked out repository directory.
     */
    val amperCheckoutRoot: Path by lazy {
        val start = Path(System.getProperty("user.dir"))

        generateSequence(start) { it.parent }
            .find { (it / ".github").exists() && (it / "CONTRIBUTING.md").exists() }
            ?: error("Unable to find Kotlin Toolchain checkout root upwards from '$start'")
    }

    /**
     * The `sources` directory in the Amper project, containing all submodules of Amper.
     */
    val amperSourcesRoot = amperCheckoutRoot / "sources"

    /**
     * The `build` directory in the Amper project, containing most of the build output.
     */
    val amperBuildOutputRoot = amperCheckoutRoot / "build"

    /**
     * The `examples` directory in the Amper project, containing all example projects that we refer to from the docs.
     */
    val examplesRoot = amperCheckoutRoot / "examples"

    /**
     * The directory containing all test projects for Amper integration tests.
     */
    val amperTestProjectsRoot = amperSourcesRoot / "test-integration/test-projects"

    /**
     * The location of the local maven repository.
     */
    val m2repository = LocalM2RepositoryFinder.findPath()

    /**
     * Path to the root directory of a cache that is reused across test runs, and across CI builds.
     *
     * * on dev machines: some place in the working copy, assuming it won't be cleared after every test run
     * * on TeamCity: a shared place on the build agent, reused between builds but potentially fully deleted if
     *   TeamCity lacks space on that agent
     */
    private val persistentCaches: Path by lazy {
        // Always run tests in a directory with a space in the name, tests quoting in a lot of places
        val dir = if (TeamCityHelper.isUnderTeamCity) {
            teamCityWeeklyCacheDir()
        } else {
            amperBuildOutputRoot / "shared test caches"
        }

        dir.createDirectories()
    }

    /**
     * The name prefix of the weekly test cache directories that we create in the TeamCity persistent cache directory.
     */
    private const val weeklyCacheDirPrefix = "amper build "

    /**
     * Returns the path to this week's test cache directory in the TeamCity persistent cache directory, and deletes
     * the cache directories of the other weeks.
     */
    private fun teamCityWeeklyCacheDir(): Path {
        val cacheRoot = TeamCityHelper.persistentCacheDirectory
        println("Persistent cache directory entries:")
        println(cacheRoot.listDirectoryEntries().joinToString("\n") { " - $it" })

        // We use the date of last Tuesday (or today if it's a Tuesday) to use a fresh cache every week.
        // This avoids accumulating things forever, and also tests regularly on clean caches.
        val thisWeekCache = cacheRoot / "$weeklyCacheDirPrefix${lastTuesday().format(LocalDate.Formats.ISO)}"

        // TeamCity only cleans up the persistent cache's top-level directories (starting from the least recently
        // updated) if the build configuration has a free disk space requirement, and ours doesn't have one on purpose:
        // we can generally reuse the existing cache, so we don't want builds to wait for free space. This means we have
        // to delete the previous weeks' caches ourselves (they are useless to us anyway).
        deleteOtherWeeklyCaches(cacheRoot, keep = thisWeekCache)

        return thisWeekCache
    }

    /**
     * Deletes all weekly test cache directories in the given [cacheRoot], except the given [keep] directory.
     *
     * Deletion failures are only reported, and don't fail the build: a leftover stale cache only wastes disk space,
     * and we get another chance to delete it on the next build.
     */
    private fun deleteOtherWeeklyCaches(cacheRoot: Path, keep: Path) {
        // TODO remove once we know we won't run any more builds with the old cache dir name
        val oldNonDatedCache = cacheRoot.resolve("amper build")

        (cacheRoot.listDirectoryEntries("$weeklyCacheDirPrefix*") + listOf(oldNonDatedCache))
            .filter { it != keep && it.isDirectory() }
            .forEach { staleCache ->
                println("Deleting stale test cache directory: $staleCache")
                try {
                    staleCache.deleteRecursively()
                } catch (e: IOException) {
                    println("Failed to delete stale test cache directory '$staleCache': $e")
                }
            }
    }

    /**
     * Returns the [LocalDate] representing last Tuesday, or today if we're Tuesday.
     */
    private fun lastTuesday(): LocalDate {
        val today = Clock.System.todayIn(TimeZone.UTC)
        val daysSinceLastTuesday = (today.dayOfWeek.ordinal - DayOfWeek.TUESDAY.ordinal + 7).rem(7)
        return today.minus(DatePeriod(days = daysSinceLastTuesday))
    }

    /**
     * Path to the root directory of a cache that is reused across test runs on dev machines, but not reused across CI
     * builds.
     *
     * * on dev machines: the same as [persistentCaches]
     * * on TeamCity: some place that is clean on every build
     */
    private val semiPersistentCaches: Path by lazy {
        if (TeamCityHelper.isUnderTeamCity) {
            TeamCityHelper.cleanTempDirectory
        } else {
            persistentCaches
        }
    }

    /**
     * Path to a directory used to cache HTTP downloads done in tests, reused across test executions and CI builds.
     */
    val persistentHttpCache by lazy { persistentCaches / "http-cache" }

    /**
     * Path to a directory used as Gradle home, reused across test executions, but not reused on the CI.
     */
    val sharedGradleHome by lazy { (semiPersistentCaches / "gradle-home").createDirectories() }

    /**
     * Path to a temporary directory that may or may not be reused across test runs.
     *
     * * on dev machines: some place in the working copy, assuming it won't be cleared after every test run
     * * on TeamCity: some place that is clean on every build
     */
    val tempDir: Path by lazy {
        val dir = if (TeamCityHelper.isUnderTeamCity) {
            // As we found out, tempDirectory from TeamCity sometimes can be not empty (e.g., locked by some process).
            // Let's make it unique and add build id (global build counter on TC server across the entire server).
            TeamCityHelper.cleanTempDirectory / "amper tests"
        } else {
            amperBuildOutputRoot / "tests temp"
        }
        dir.createDirectories()
        println("Temp dir for tests: $dir")
        dir
    }

    /**
     * Path to the root directory of a cache that is reused across test runs on dev machines, but not reused across CI
     * builds.
     *
     * * on dev machines: some place in the working copy, assuming it won't be cleared after every test run
     * * on TeamCity: some place that is removed after the build
     */
    val userCacheRoot: Path by lazy { semiPersistentCaches / "amper-cache" }

    /**
     * A root directory to store Android SDK data and setup caches, reused between test runs and between CI builds.
     *
     * **Note:** consumers should generally prefer [org.jetbrains.amper.test.android.AndroidTools.prepareForTests].
     */
    internal val androidTestCache: Path by lazy { persistentCaches / "android" }
}
