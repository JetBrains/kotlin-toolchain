/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipEntriesTest {

    @TempDir
    lateinit var tempDir: Path

    private val sourceZip: Path by lazy {
        zip(
            name = "source.zip",
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
        val text = sourceZip.useZipFile { zipFile ->
            zipFile.useZipEntryStream("nativeMain/default/manifest") {
                it?.readBytes()?.decodeToString()
            }
        }

        assertEquals("native manifest", text)
    }

    /**
     * This test checks that the block isn't called for an absent entry.
     */
    @Test
    fun `absent entry gives a null input stream`() {
        val result = sourceZip.useZipFile { zipFile ->
            zipFile.useZipEntryStream("jvmMain/default/manifest") { stream ->
                if (stream == null) "good" else "bad"
            }
        }
        assertEquals("good", result, "the block should get a null InputStream when the entry is absent")
    }

    /**
     * This test checks that the entries of a directory are copied to the root of the new zip,
     * and the entries outside it aren't copied.
     */
    @Test
    fun `directory is copied to zip root`() {
        val target = tempDir / "commonMain.klib"
        ZipOutputStream(target.outputStream()).use { zipOutput ->
            sourceZip.useZipFile { zip ->
                zip.copyZipDirectoryTo("commonMain", zipOutput)
            }
        }

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
        ZipOutputStream(target.outputStream()).use { zipOutput ->
            source.useZipFile { zip ->
                zip.copyZipDirectoryTo("lib", zipOutput)
            }
        }

        target.useZipFile { zip ->
            for (name in ["default/", "default/manifest"]) {
                val entry = zip.getEntry(name)
                assertEquals(time, entry.time, "time of $name")
                assertEquals("comment of lib/$name", entry.comment, "comment of $name")
            }
        }
    }

    @Test
    fun `child directories of directory are listed`() {
        assertEquals(["lib-cinterop-a", "lib-cinterop-b"], sourceZip.useZipFile { zipFile ->
            zipFile.listDirectSubdirectoriesOf(Path("nativeMain-cinterop"))
        })
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ".", "./"])
    fun `child directories of root path are listed`(path: String) {
        assertEquals(["commonMain", "nativeMain", "nativeMain-cinterop", "META-INF"], sourceZip.useZipFile { zipFile ->
            zipFile.listDirectSubdirectoriesOf(Path(path))
        })
    }

    /**
     * This test checks that a directory is listed even if it has no dedicated directory entry,
     * as long as some entries exist inside it.
     */
    @Test
    fun `child directories without dedicated directory entries are listed`() {
        sourceZip.useZipFile { zipFile ->
            assertEquals(["default"], zipFile.listDirectSubdirectoriesOf(Path("commonMain")))
            assertEquals(["default"], zipFile.listDirectSubdirectoriesOf(Path("nativeMain")))
        }
    }

    /**
     * This test checks that directories are listed even when the zip contains no directory entries at all,
     * and that files directly inside the queried directory are not listed.
     */
    @Test
    fun `child directories are listed without any directory entries`() {
        val zip = zip(
            name = "no-directory-entries.zip",
            "dirA/file1" to "1",
            "dirA/nested/file2" to "2",
            "dirB/file3" to "3",
            "rootFile" to "4",
        )

        zip.useZipFile { zipFile ->
            assertEquals(["dirA", "dirB"], zipFile.listDirectSubdirectoriesOf(Path("")))
            assertEquals(["nested"], zipFile.listDirectSubdirectoriesOf(Path("dirA")))
        }
    }

    /**
     * Writes a zip file with the given entries: names mapped to their text, or to `null` for directories.
     */
    private fun zip(name: String, vararg entries: Pair<String, String?>): Path = (tempDir / name).also { path ->
        ZipOutputStream(path.outputStream()).use { zip ->
            for ([name, contents] in entries) {
                zip.putNextEntry(ZipEntry(name))
                contents?.let { zip.write(it.encodeToByteArray()) }
                zip.closeEntry()
            }
        }
    }
}
