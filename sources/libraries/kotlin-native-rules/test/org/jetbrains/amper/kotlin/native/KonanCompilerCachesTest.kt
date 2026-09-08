/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.kotlin.native

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KonanCompilerCachesTest {

    private val macosArm64 = KonanPlatform("macos_arm64")
    private val macosX64 = KonanPlatform("macos_x64")
    private val iosArm64 = KonanPlatform("ios_arm64")
    private val iosSimulatorArm64 = KonanPlatform("ios_simulator_arm64")
    private val mingwX64 = KonanPlatform("mingw_x64")
    private val linuxX64 = KonanPlatform("linux_x64")

    @Test
    fun `cacheable targets are read for the given host`() {
        // The real layout of konan.properties: whitespace-separated lists continued with backslashes
        val dist = distributionWithKonanProperties("""
            cacheableTargets.macos_x64 = \
              macos_x64 \
              ios_x64 \
              ios_arm64

            cacheableTargets.macos_arm64 = \
              macos_arm64 \
              ios_simulator_arm64 \
              ios_arm64

            cacheableTargets.linux_x64 = \
              linux_x64

            cacheableTargets.mingw_x64 =

            optInCacheableTargets =
        """.trimIndent())

        assertEquals(
            setOf(macosArm64, iosSimulatorArm64, iosArm64),
            dist.cacheableTargets(host = macosArm64),
        )
        assertEquals(
            setOf(macosX64, KonanPlatform("ios_x64"), iosArm64),
            dist.cacheableTargets(host = macosX64),
        )
        assertEquals(setOf(linuxX64), dist.cacheableTargets(host = linuxX64))
    }

    @Test
    fun `a host with an empty cacheable targets list supports no caches`() {
        val dist = distributionWithKonanProperties("""
            cacheableTargets.mingw_x64 =
            optInCacheableTargets =
        """.trimIndent())

        assertEquals(emptySet(), dist.cacheableTargets(host = mingwX64))
        assertFalse(dist.supportsCompilerCachesFor(target = mingwX64, host = mingwX64))
    }

    @Test
    fun `an unknown host supports no caches`() {
        val dist = distributionWithKonanProperties("""
            cacheableTargets.macos_arm64 = macos_arm64
            optInCacheableTargets =
        """.trimIndent())

        assertEquals(emptySet(), dist.cacheableTargets(host = KonanPlatform("solaris_sparc")))
    }

    @Test
    fun `targets requiring an explicit opt-in are not cached by default`() {
        // This mirrors KGP's 'defaultCacheKindForTarget', which only returns STATIC for targets that are
        // cacheable *and* not in the opt-in list.
        val dist = distributionWithKonanProperties("""
            cacheableTargets.macos_arm64 = \
              macos_arm64 \
              ios_arm64
            optInCacheableTargets.macos_arm64 = ios_arm64
        """.trimIndent())

        assertTrue(dist.supportsCompilerCachesFor(target = macosArm64, host = macosArm64))
        assertFalse(dist.supportsCompilerCachesFor(target = iosArm64, host = macosArm64))
    }

    @Test
    fun `property references are resolved`() {
        // konan.properties uses '$'-prefixed references to other properties in several places, so the list
        // parsing has to follow them.
        val dist = distributionWithKonanProperties("""
            appleTargets = macos_arm64 ios_arm64
            cacheableTargets.macos_arm64 = ${'$'}appleTargets ios_simulator_arm64
            optInCacheableTargets =
        """.trimIndent())

        assertEquals(
            setOf(macosArm64, iosArm64, iosSimulatorArm64),
            dist.cacheableTargets(host = macosArm64),
        )
    }

    @Test
    fun `self-referencing properties do not cause infinite recursion`() {
        val dist = distributionWithKonanProperties("""
            cacheableTargets.macos_arm64 = ${'$'}cacheableTargets.macos_arm64 macos_arm64
            optInCacheableTargets =
        """.trimIndent())

        assertEquals(setOf(macosArm64), dist.cacheableTargets(host = macosArm64))
    }

    @Test
    fun `a distribution without konan properties supports no caches`() {
        val dist = KonanDistribution(homeDir = createTempDirectory("konan-dist"), kotlinVersion = "2.4.0")

        assertEquals(emptySet(), dist.cacheableTargets(host = macosArm64))
        assertFalse(dist.supportsCompilerCachesFor(target = macosArm64, host = macosArm64))
    }

    @Test
    fun `compiler caches root is inside the distribution next to the shipped platform lib caches`() {
        val home = createTempDirectory("konan-dist")
        val dist = KonanDistribution(homeDir = home, kotlinVersion = "2.4.0")

        assertEquals(home / "klib" / "cache", dist.compilerCachesRoot)
    }

    private fun distributionWithKonanProperties(content: String): KonanDistribution {
        val home = createTempDirectory("konan-dist")
        val propertiesFile: Path = (home / "konan").createDirectories() / "konan.properties"
        propertiesFile.writeText(content)
        return KonanDistribution(homeDir = home, kotlinVersion = "2.4.0")
    }
}
