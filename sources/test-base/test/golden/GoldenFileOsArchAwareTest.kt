/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.golden

import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

class GoldenFileOsArchAwareTest {

    @TempDir
    lateinit var goldenFilesDir: Path

    private val baseName = "myGoldenFile.tree.txt"

    @Test
    fun `the os+arch-specific variant wins over all the others`() {
        createGoldenFiles("myGoldenFile.tree-mac-arm64.txt", "myGoldenFile.tree-mac.txt", baseName)

        assertResolvedTo("myGoldenFile.tree-mac-arm64.txt", OsFamily.MacOs, Arch.Arm64)
    }

    @Test
    fun `the os-specific variant is used when there is no variant for the current arch`() {
        createGoldenFiles("myGoldenFile.tree-mac-arm64.txt", "myGoldenFile.tree-mac.txt", baseName)

        assertResolvedTo("myGoldenFile.tree-mac.txt", OsFamily.MacOs, Arch.X64)
    }

    @Test
    fun `the os-agnostic variant is used when there is no variant for the current os`() {
        createGoldenFiles("myGoldenFile.tree-mac-arm64.txt", "myGoldenFile.tree-mac.txt", baseName)

        assertResolvedTo(baseName, OsFamily.Windows, Arch.X64)
        assertResolvedTo(baseName, OsFamily.Linux, Arch.Arm64)
    }

    @Test
    fun `the os+arch-specific variant is used even without the os-specific one`() {
        createGoldenFiles("myGoldenFile.tree-linux-arm64.txt", baseName)

        assertResolvedTo("myGoldenFile.tree-linux-arm64.txt", OsFamily.Linux, Arch.Arm64)
        assertResolvedTo(baseName, OsFamily.Linux, Arch.X64)
    }

    @Test
    fun `the os-agnostic variant is returned even if it doesn't exist`() {
        // golden files are created by the tests themselves when they are missing, so the path must be returned as is
        assertResolvedTo(baseName, OsFamily.MacOs, Arch.Arm64)
    }

    @Test
    fun `variants of unsupported OSes are ignored`() {
        createGoldenFiles("myGoldenFile.tree-mac.txt", baseName)

        assertResolvedTo(baseName, OsFamily.FreeBSD, Arch.X64)
    }

    private fun createGoldenFiles(vararg names: String) = names.forEach { (goldenFilesDir / it).createFile() }

    private fun assertResolvedTo(expectedFileName: String, osFamily: OsFamily, arch: Arch) = assertEquals(
        expected = goldenFilesDir / expectedFileName,
        actual = goldenFilesDir.goldenFileOsArchAware(baseName, osFamily, arch),
        message = "Unexpected golden file resolved for $osFamily/$arch",
    )
}
