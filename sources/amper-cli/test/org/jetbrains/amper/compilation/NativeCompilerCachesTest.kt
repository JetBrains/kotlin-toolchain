/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.kotlin.native.KonanDistribution
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeCompilerCachesTest {

    private val macOsArm64Host = TestSystemInfo(OsFamily.MacOs, Arch.Arm64)
    private val linuxHost = TestSystemInfo(OsFamily.Linux, Arch.X64)
    private val windowsHost = TestSystemInfo(OsFamily.Windows, Arch.X64)

    private val dependencyRoot = Path("/home/me/.cache/JetBrains/Kotlin")
    private val icDir = Path("/project/build/kotlin-native-ic-cache/link-task")
    private val emptyRoot = Path("/tmp/amper/empty-native-auto-cache-root")

    @Test
    fun `dependency caching only passes the auto-cache roots`() {
        val caches = cachesFor(dependencyRoots = [dependencyRoot], compileIncrementally = false)

        assertEquals(
            ["-Xauto-cache-from=${dependencyRoot.pathString()}"],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `every dependency root is passed separately`() {
        val otherRoot = Path("/opt/shared-klibs")
        val caches = cachesFor(dependencyRoots = [dependencyRoot, otherRoot], compileIncrementally = false)

        assertEquals(
            [
                "-Xauto-cache-from=${dependencyRoot.pathString()}",
                "-Xauto-cache-from=${otherRoot.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `incremental compilation always passes the enabling flag together with the cache dir`() {
        // The compiler rejects the arguments when only one of the two is present, so they must never be split.
        val caches = cachesFor(dependencyRoots = [], compileIncrementally = true)

        assertEquals(
            [
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDir.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `dependency caching and incremental compilation can be combined`() {
        val caches = cachesFor(dependencyRoots = [dependencyRoot], compileIncrementally = true)

        assertEquals(
            [
                "-Xauto-cache-from=${dependencyRoot.pathString()}",
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDir.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `no caches when nothing is enabled`() {
        assertNull(cachesFor(dependencyRoots = [], compileIncrementally = false))
    }

    @Test
    fun `no caches for optimized binaries`() {
        // The compiler ignores all caches "with global optimizations", so passing the flags would only be misleading.
        assertNull(
            cachesFor(
                dependencyRoots = [dependencyRoot],
                compileIncrementally = true,
                optimizationEnabled = true,
            )
        )
    }

    @Test
    fun `no caches when producing a klib`() {
        // Caches belong to the second compilation stage, which doesn't run when producing a klib. This covers both
        // the leaf klib compilation and the shared native metadata compilation.
        assertNull(
            cachesFor(
                dependencyRoots = [dependencyRoot],
                compileIncrementally = true,
                compilationType = KotlinCompilationType.LIBRARY,
            )
        )
    }

    @Test
    fun `caches are used for iOS frameworks`() {
        val caches = cachesFor(
            dependencyRoots = [dependencyRoot],
            compileIncrementally = false,
            target = Platform.IOS_SIMULATOR_ARM64,
            compilationType = KotlinCompilationType.IOS_FRAMEWORK,
        )

        assertEquals(["-Xauto-cache-from=${dependencyRoot.pathString()}"], caches?.compilerArgs())
    }

    @Test
    fun `no caches for a target that the distribution doesn't support caching for`() {
        // linuxX64 is not in the cacheable targets of a macOS host, so cross-compiling to it can't use caches.
        assertNull(
            cachesFor(
                dependencyRoots = [dependencyRoot],
                compileIncrementally = true,
                target = Platform.LINUX_X64,
            )
        )
    }

    @Test
    fun `no caches on a host that doesn't support caching at all`() {
        assertNull(
            cachesFor(
                dependencyRoots = [dependencyRoot],
                compileIncrementally = true,
                target = Platform.MINGW_X64,
                system = windowsHost,
            )
        )
    }

    @Test
    fun `no caches on a Linux if incremental cache dir contains whitespace and kotlin version has no fix yet`() {
        // The Kotlin/Native cache builder invokes 'ar' through /bin/sh without quoting the paths.
        assertNull(
            cachesFor(
                dependencyRoots = [],
                compileIncrementally = true,
                incrementalCacheDir = Path("/home/me/my project/build/kotlin-native-ic-cache"),
                target = Platform.LINUX_X64,
                system = linuxHost,
                kotlinVersion = "2.2.0",
            )
        )
    }

    @Test
    fun `no caches on a Linux if a dependency root contains whitespace and kotlin version has no fix yet`() {
        assertNull(
            cachesFor(
                dependencyRoots = [Path("/home/my user/.cache/JetBrains/Kotlin")],
                compileIncrementally = false,
                target = Platform.LINUX_X64,
                system = linuxHost,
                kotlinVersion = "2.2.0",
            )
        )
    }

    @Test
    fun `no caches on a Linux if the distribution path contains whitespace and kotlin version has no fix yet`() {
        assertNull(
            cachesFor(
                dependencyRoots = [dependencyRoot],
                compileIncrementally = true,
                distributionHome = createTempDirectory("konan dist with spaces"),
                target = Platform.LINUX_X64,
                system = linuxHost,
                kotlinVersion = "2.2.0",
            )
        )
    }

    @Test
    fun `whitespace in the incremental cache dir is only a problem on Linux`() {
        // Only the Linux toolchain uses 'ar' archive scripts, the Apple one handles such paths just fine.
        val icDirWithSpaces = Path("/Users/me/my project/build/kotlin-native-ic-cache")
        val caches = cachesFor(
            dependencyRoots = [],
            compileIncrementally = true,
            incrementalCacheDir = icDirWithSpaces,
        )

        assertEquals(
            [
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDirWithSpaces.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `caches are used on a Linux host when no path contains whitespace`() {
        // Guards against the whitespace checks disabling caches on Linux altogether.
        val caches = cachesFor(
            dependencyRoots = [dependencyRoot],
            compileIncrementally = true,
            target = Platform.LINUX_X64,
            system = linuxHost,
        )

        assertEquals(
            [
                "-Xauto-cache-from=${dependencyRoot.pathString()}",
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDir.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `whitespace in paths is not a problem for a compiler that supports it on Linux`() {
        // The fix for KT-86824 is available in 2.4.20-Beta2. That version still can't build caches (KT-88316), so
        // getting the prebuilt ones instead of null is what shows that the whitespace check didn't reject it.
        val caches = cachesFor(
            dependencyRoots = [Path("/home/my user/.cache/JetBrains/Kotlin")],
            compileIncrementally = true,
            target = Platform.LINUX_X64,
            system = linuxHost,
            kotlinVersion = "2.4.20-Beta2",
        )

        assertEquals(
            [
                "-Xauto-cache-from=${emptyRoot.pathString()}",
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDir.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `earlier pre-releases of the fixed compiler version are still affected on Linux`() {
        assertNull(
            cachesFor(
                dependencyRoots = [Path("/home/my user/.cache/JetBrains/Kotlin")],
                compileIncrementally = false,
                target = Platform.LINUX_X64,
                system = linuxHost,
                kotlinVersion = "2.4.20-Beta1",
            )
        )
    }

    @Test
    fun `dependencies are not cached with a compiler that crashes on caching them`() {
        // Caching dependencies crashes the compiler (KT-88316), but the distribution's prebuilt caches need no
        // caching, and incremental compilation is left alone.
        val caches = cachesFor(
            dependencyRoots = [dependencyRoot],
            compileIncrementally = true,
            kotlinVersion = "2.4.10",
        )

        assertEquals(
            [
                // the empty root: nothing is cached from it, it only unlocks the distribution's prebuilt caches
                "-Xauto-cache-from=${emptyRoot.pathString()}",
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDir.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `no auto-cache root at all when dependency caching is off and the compiler crashes on caching them`() {
        // Prebuilt caches are caches of dependencies, so they must not be used when the user opted out of those.
        val caches = cachesFor(
            dependencyRoots = [],
            compileIncrementally = true,
            kotlinVersion = "2.4.10",
        )

        assertEquals(
            [
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDir.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    @Test
    fun `dependencies are cached from the first compiler version that survives caching them`() {
        val caches = cachesFor(
            dependencyRoots = [dependencyRoot],
            compileIncrementally = true,
            kotlinVersion = "2.4.20-RC2",
        )

        assertEquals(
            [
                "-Xauto-cache-from=${dependencyRoot.pathString()}",
                "-Xenable-incremental-compilation",
                "-Xic-cache-dir=${icDir.pathString()}",
            ],
            caches?.compilerArgs(),
        )
    }

    private fun cachesFor(
        dependencyRoots: List<Path>,
        compileIncrementally: Boolean,
        optimizationEnabled: Boolean = false,
        compilationType: KotlinCompilationType = KotlinCompilationType.BINARY,
        target: Platform = Platform.MACOS_ARM64,
        system: SystemInfo = macOsArm64Host,
        incrementalCacheDir: Path = icDir,
        distributionHome: Path = createTempDirectory("konan-dist"),
        kotlinVersion: String = "2.4.20-RC2",
    ): NativeCompilerCaches? = nativeCompilerCachesFor(
        konanDistribution = testDistribution(distributionHome, kotlinVersion),
        target = target,
        system = system,
        compilationType = compilationType,
        optimizationEnabled = optimizationEnabled,
        dependencyCacheRoots = dependencyRoots,
        emptyAutoCacheRoot = emptyRoot,
        compileIncrementally = compileIncrementally,
        incrementalCacheDir = incrementalCacheDir,
    )

    /**
     * A distribution advertising the same cacheable targets as the real Kotlin/Native 2.4.10 distribution.
     */
    private fun testDistribution(home: Path, kotlinVersion: String): KonanDistribution {
        ((home / "konan").createDirectories() / "konan.properties").writeText(
            """
            cacheableTargets.macos_arm64 = \
              macos_arm64 \
              ios_simulator_arm64 \
              ios_arm64
            cacheableTargets.linux_x64 = \
              linux_x64
            cacheableTargets.mingw_x64 =
            optInCacheableTargets =
            """.trimIndent()
        )
        (home / "klib" / "cache").createDirectories() // where a real distribution ships its prebuilt caches
        return KonanDistribution(homeDir = home, kotlinVersion = kotlinVersion)
    }

    private fun Path.pathString() = toString()

    private class TestSystemInfo(override val family: OsFamily, override val arch: Arch) : SystemInfo
}
