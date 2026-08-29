/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.stdlib.hashing.sha256String
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SdkmanArchiveTest {
    @Test
    fun `sdkman archive contains wrappers under bin and license`() {
        val tempDir = createTempDirectory()
        try {
            val cliTgz = tempDir / "cli.tgz"
            val licenseFile = tempDir / "LICENSE.txt"
            val archive = tempDir / "kotlin.zip"

            cliTgz.writeText("fake distribution")
            licenseFile.writeText("Apache License text")

            buildSdkmanArchive(
                archive = archive,
                distribution = object : Distribution {
                    override val cliTgz: Path = cliTgz
                    override val wrappersDir: Path = tempDir / "wrappers"
                    override val installersDir: Path = tempDir / "installers"
                },
                licenseFile = licenseFile,
            )

            val rootDirectory = "kotlintoolchain-${AmperBuild.mavenVersion}"
            ZipFile.builder().setPath(archive).get().use { zip ->
                val entries = zip.entries.asSequence().associateBy { it.name }

                assertContains(entries.keys, "$rootDirectory/")
                assertContains(entries.keys, "$rootDirectory/bin/")
                assertExecutable(entries.getValue("$rootDirectory/bin/kotlin"))
                assertNotExecutable(entries.getValue("$rootDirectory/bin/kotlin.bat"))
                assertNotExecutable(entries.getValue("$rootDirectory/license"))

                assertEquals(
                    licenseFile.readBytes().decodeToString(),
                    zip.readText(entries.getValue("$rootDirectory/license")),
                )
                assertContains(
                    zip.readText(entries.getValue("$rootDirectory/bin/kotlin")),
                    cliTgz.sha256String(),
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun ZipFile.readText(entry: ZipArchiveEntry): String =
        getInputStream(entry).use { it.readAllBytes().decodeToString() }

    private fun assertExecutable(entry: ZipArchiveEntry) {
        assertTrue(entry.unixMode and executableBits != 0, "${entry.name} should be executable")
    }

    private fun assertNotExecutable(entry: ZipArchiveEntry) {
        assertEquals(0, entry.unixMode and executableBits, "${entry.name} should not be executable")
    }

    private fun <K, V> Map<K, V>.getValue(key: K): V = assertNotNull(get(key), "$key should exist")
}

private val executableBits = "111".toInt(8)
