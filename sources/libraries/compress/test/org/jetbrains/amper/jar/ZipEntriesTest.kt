/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipEntriesTest {

    @TempDir
    lateinit var tempDir: Path

    private val sourceZip: Path by lazy {
        zip(
            "source.zip",
            "commonMain/" to null,
            "commonMain/default/" to null,
            "commonMain/default/manifest" to "common manifest",
            "nativeMain/" to null,
            "nativeMain/default/manifest" to "native manifest",
            "nativeMain-cinterop/" to null,
            "nativeMain-cinterop/lib-cinterop-a/" to null,
            "nativeMain-cinterop/lib-cinterop-a/default/manifest" to "a manifest",
            "nativeMain-cinterop/lib-cinterop-b/" to null,
            "META-INF/kotlin-project-structure-metadata.json" to "{}",
        )
    }

    /**
     * This test checks that the content of an existing entry is passed to the block.
     */
    @Test
    fun `existing entry is read`() {
        val text = withZipEntry(sourceZip, "nativeMain/default/manifest") { it.readBytes().decodeToString() }

        assertEquals("native manifest", text)
    }

    /**
     * This test checks that the block isn't called for an absent entry.
     */
    @Test
    fun `absent entry is null`() {
        assertNull(withZipEntry(sourceZip, "jvmMain/default/manifest") { error("must not be called") })
    }

    /**
     * This test checks that the entries of a directory are copied to the root of the new zip,
     * and the entries outside it aren't copied.
     */
    @Test
    fun `directory is copied to zip root`() {
        val target = tempDir / "commonMain.klib"
        target.outputStream().use { copyZipDirectory(sourceZip, "commonMain", it) }

        assertEquals(["default/", "default/manifest"], target.readZipEntryNames())
        assertEquals("common manifest", target.readZipEntry("default/manifest").text)
    }

    /**
     * This test checks that the copied entries keep the time and the comment of the source entries,
     * so that copying the same directory twice gives the same zip.
     */
    @Test
    fun `copied entries keep time and comment`() {
        val time = Instant.parse("2020-01-02T03:04:06Z").toEpochMilli()
        val source = tempDir / "timed.zip"
        ZipOutputStream(source.outputStream()).use { zip ->
            for (name in ["lib/default/", "lib/default/manifest"]) {
                zip.putNextEntry(ZipEntry(name).apply { this.time = time; comment = "comment of $name" })
                zip.closeEntry()
            }
        }

        val target = tempDir / "lib.klib"
        target.outputStream().use { copyZipDirectory(source, "lib", it) }

        ZipFile(target.toFile()).use { zip ->
            for (name in ["default/", "default/manifest"]) {
                val entry = zip.getEntry(name)
                assertEquals(time, entry.time, "time of $name")
                assertEquals("comment of lib/$name", entry.comment, "comment of $name")
            }
        }
    }

    /**
     * This test checks that the direct subdirectories of a directory are listed, without nested ones.
     */
    @Test
    fun `nested directories of directory are listed`() {
        assertEquals(setOf("lib-cinterop-a", "lib-cinterop-b"), nestedDirectories(sourceZip, "nativeMain-cinterop"))
    }

    /**
     * This test checks that the root directories are listed when no directory is given.
     */
    @Test
    fun `root directories are listed`() {
        assertEquals(
            setOf("commonMain", "nativeMain", "nativeMain-cinterop"),
            nestedDirectories(sourceZip),
        )
    }

    /**
     * Writes a zip file with the given entries: names mapped to their text, or to `null` for directories.
     */
    private fun zip(name: String, vararg entries: Pair<String, String?>): Path = (tempDir / name).also { path ->
        ZipOutputStream(path.outputStream()).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry.first))
                entry.second?.let { zip.write(it.encodeToByteArray()) }
                zip.closeEntry()
            }
        }
    }
}
