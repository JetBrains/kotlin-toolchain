/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.kotlin.native.KonanDistribution
import org.jetbrains.amper.kotlin.native.konanHostPlatform
import org.jetbrains.amper.kotlin.native.supportsCompilerCachesFor
import org.jetbrains.amper.kotlin.native.toKonanPlatform
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import java.nio.file.Path
import kotlin.io.path.pathString

private val logger = org.slf4j.LoggerFactory.getLogger("compilation/NativeCompilerCaches")

/**
 * The Kotlin/Native compiler caches to use it for a single link (second-stage) compilation.
 *
 * By default, the compiler compiles all klibs (including all external dependencies) into a single binary, which is
 * slow. These caches let it compile klibs to object files once and then link those together, which is much faster.
 *
 * See https://github.com/JetBrains/kotlin/blob/master/docs/native/compilation-model.md#compiler-caches
 */
internal data class NativeCompilerCaches(
    /**
     * The root directories under which klibs are eligible for automatic caching.
     *
     * The compiler caches the klibs it finds under these roots and reuses those caches across compilations (and
     * across projects). Only *external* dependencies belong here: klibs compiled from project sources are handled
     * by [incrementalCacheDir] instead.
     *
     * The caches themselves are not written here. They go to the compiler's own cache directory, which is inside
     * the Kotlin/Native distribution (see [org.jetbrains.amper.kotlin.native.KonanDistribution.compilerCachesRoot]).
     *
     * Note: Specifying auto-cache directory automatically instructs the compiler to use a pre-built cache of kotlin-stdlib
     * which is a part of konan distribution.
     */
    val autoCacheableFrom: List<Path>,
    /**
     * The directory for the per-file incremental caches of the klibs compiled from this project's sources, or null
     * if incremental compilation of the second stage is disabled.
     */
    val incrementalCacheDir: Path?,
) {
    fun compilerArgs(): List<String> = buildList {
        autoCacheableFrom.forEach {
            add("-Xauto-cache-from=${it.pathString}")
        }
        // TODO KGP also passes -Xbackend-threads to parallelize codegen while caches are being built, defaulting
        //  to 4 (kotlin.native.parallelThreads). The compiler itself defaults to 1, so cache population is
        //  single-threaded here. We already run link tasks in parallel, so the right value needs measuring first.
        //  add("-Xbackend-threads=$backendThreads")
        if (incrementalCacheDir != null) {
            // The compiler requires both of these arguments together, and fails when only one is present.
            add("-Xenable-incremental-compilation")
            add("-Xic-cache-dir=${incrementalCacheDir.pathString}")
        }
    }
}

/**
 * Returns the compiler caches settings to use when linking a binary for [target] with the given [konanDistribution], or null
 * if caches cannot or should not be used.
 *
 * Caches are unavailable in the following cases:
 * * when producing a klib ([KotlinCompilationType.LIBRARY]), because caches belong to the second compilation stage,
 *   which doesn't run at all in that case. This covers both leaf klib compilations and shared native metadata
 *   compilations.
 * * when optimizations are enabled, because the compiler ignores all caches "with global optimizations"
 * * when the Kotlin/Native distribution doesn't advertise cache support for [target] on this host
 * * when neither dependency caching nor incremental compilation is requested
 * * when a path involved in caching contains whitespace on a Linux host (see [cacheBuilderSupports])
 *
 * Compilers that crash while caching dependencies (due to KT-88316) only get the distribution's prebuilt caches, see
 * [autoCacheableRootsFor].
 *
 * This is aligned with the conditions used by the Kotlin Gradle Plugin in `KotlinNativeLink`.
 */
internal fun nativeCompilerCachesFor(
    konanDistribution: KonanDistribution,
    target: Platform,
    system: SystemInfo = SystemInfo.CurrentHost,
    compilationType: KotlinCompilationType,
    optimizationEnabled: Boolean,
    dependencyCacheRoots: List<Path>,
    emptyAutoCacheRoot: Path,
    compileIncrementally: Boolean,
    incrementalCacheDir: Path,
): NativeCompilerCaches? {
    if (compilationType == KotlinCompilationType.LIBRARY) return null
    if (optimizationEnabled) return null
    if (dependencyCacheRoots.isEmpty() && !compileIncrementally) return null

    val host = konanHostPlatform(system) ?: return null
    if (!konanDistribution.supportsCompilerCachesFor(target = target.toKonanPlatform(), host = host)) return null

    // The paths the cache builder reads the klibs from and writes the caches to.
    val cachePaths = buildList {
        addAll(dependencyCacheRoots)
        add(konanDistribution.homeDir)
        if (compileIncrementally) add(incrementalCacheDir)
    }
    if (!cacheBuilderSupports(cachePaths, system, konanDistribution.kotlinVersion)) return null

    return NativeCompilerCaches(
        autoCacheableFrom = autoCacheableRootsFor(konanDistribution, dependencyCacheRoots, emptyAutoCacheRoot),
        incrementalCacheDir = incrementalCacheDir.takeIf { compileIncrementally },
    )
}

/**
 * The first Kotlin version that can cache external dependencies without crashing.
 * See https://youtrack.jetbrains.com/issue/KT-88316
 * TODO KT-88316 cache dependencies unconditionally once the minimum supported Kotlin version reaches 2.4.20-RC2.
 */
private val MinKotlinVersionForCachingDependencies = ComparableVersion("2.4.20-RC2")

/**
 * Returns the roots to cache external dependencies from, given the compiler of [konanDistribution] that will run.
 *
 * Compilers older than [MinKotlinVersionForCachingDependencies] only get [emptyAutoCacheRoot]: no klib is eligible
 * for caching under an empty directory, so nothing is ever cached, but the mere presence of the option makes the
 * compiler use the caches prebuilt in its distribution (the standard library and the platform libraries).
 */
private fun autoCacheableRootsFor(
    konanDistribution: KonanDistribution,
    dependencyCacheRoots: List<Path>,
    emptyAutoCacheRoot: Path,
): List<Path> {
    if (dependencyCacheRoots.isEmpty()) return []
    if (ComparableVersion(konanDistribution.kotlinVersion) >= MinKotlinVersionForCachingDependencies) {
        return dependencyCacheRoots
    }

    logger.debug(
        "Only using the prebuilt Kotlin/Native caches, because caching dependencies might crash the Kotlin {} compiler " +
                "(see https://youtrack.jetbrains.com/issue/KT-88316)", konanDistribution.kotlinVersion,
    )
    return [emptyAutoCacheRoot]
}

/**
 * The first Kotlin version whose Linux cache builder supports whitespace in paths (fixed in KT-86824).
 *
 * todo (AB):
 *  Note that the fix of KT-86824 is tagged for a cherry-pick, so an earlier 2.4.x version may support it as well.
 *  Condition might be relaxed after fix is backported.
 */
private val MinKotlinVersionSupportingSpacesOnLinux = ComparableVersion("2.4.20-Beta2")

/**
 * Whether the Kotlin/Native cache builder of the given [kotlinVersion] can handle the given [paths] on a host with
 * the given [system].
 *
 * TODO KT-86824 remove this workaround once we require Kotlin >= 2.4.2
 */
private fun cacheBuilderSupports(paths: List<Path>, system: SystemInfo, kotlinVersion: String): Boolean {
    if (system.family != OsFamily.Linux) return true
    if (ComparableVersion(kotlinVersion) >= MinKotlinVersionSupportingSpacesOnLinux) return true

    val unsupportedPaths = paths.filter { path -> path.pathString.any { it.isWhitespace() } }
    if (unsupportedPaths.isEmpty()) return true

    logger.debug(
        "Disabling the Kotlin/Native compiler caches because the cache builder of Kotlin {} cannot handle the " +
                "whitespace in the following paths on Linux: {}",
        kotlinVersion, unsupportedPaths.joinToString { "'$it'" },
    )
    return false
}