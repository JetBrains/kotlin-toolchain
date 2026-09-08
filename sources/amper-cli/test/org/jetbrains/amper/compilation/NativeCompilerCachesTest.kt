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
    private val windowsHost = TestSystemInfo(OsFamily.Windows, Arch.X64)

    private val dependencyRoot = Path("/home/me/.cache/JetBrains/Kotlin")
    private val icDir = Path("/project/build/kotlin-native-ic-cache/link-task")

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

    private fun cachesFor(
        dependencyRoots: List<Path>,
        compileIncrementally: Boolean,
        optimizationEnabled: Boolean = false,
        compilationType: KotlinCompilationType = KotlinCompilationType.BINARY,
        target: Platform = Platform.MACOS_ARM64,
        system: SystemInfo = macOsArm64Host,
    ): NativeCompilerCaches? = nativeCompilerCachesFor(
        konanDistribution = testDistribution(),
        target = target,
        system = system,
        compilationType = compilationType,
        optimizationEnabled = optimizationEnabled,
        dependencyCacheRoots = dependencyRoots,
        compileIncrementally = compileIncrementally,
        incrementalCacheDir = icDir,
    )

    /**
     * A distribution advertising the same cacheable targets as the real Kotlin/Native 2.4.10 distribution.
     */
    private fun testDistribution(): KonanDistribution {
        val home = createTempDirectory("konan-dist")
        ((home / "konan").createDirectories() / "konan.properties").writeText(
            """
            cacheableTargets.macos_arm64 = \
              macos_arm64 \
              ios_simulator_arm64 \
              ios_arm64
            cacheableTargets.mingw_x64 =
            optInCacheableTargets =
            """.trimIndent()
        )
        return KonanDistribution(homeDir = home, kotlinVersion = "2.4.0")
    }

    private fun Path.pathString() = toString()

    private class TestSystemInfo(override val family: OsFamily, override val arch: Arch) : SystemInfo
}
