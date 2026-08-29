/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.ZipEntry.DEFLATED
import java.util.zip.ZipEntry.STORED
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipTest {

    @TempDir
    lateinit var tempDir: Path

    private val zipFile: Path get() = tempDir / "out.zip"

    private val inputsDir: Path by lazy { (tempDir / "inputs").createDirectory() }

    @Test
    fun `ZipInput rejects absolute destination paths`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ZipInput(path = inputsDir, destPathInArchive = inputsDir.toAbsolutePath())
        }
        assertTrue(
            actual = exception.message?.contains("destPathInArchive must be a relative path") == true,
            message = "Unexpected exception message: ${exception.message}",
        )
    }

    @Test
    fun `writes a single file input at its destination path`() {
        val file = inputsDir.createTextFile("hello.txt", text = "hello")

        zipFile.writeZip([ZipInput(path = file, destPathInArchive = Path("hello.txt"))])

        val expectedNames: List<String> = ["hello.txt"]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
        assertEquals("hello", zipFile.readZipEntry("hello.txt").text)
    }

    @Test
    fun `renames a file input according to its destination path`() {
        val file = inputsDir.createTextFile("hello.txt", text = "hello")

        zipFile.writeZip([ZipInput(path = file, destPathInArchive = Path("renamed/greeting.txt"))])

        // no intermediate directory entries are created for a single file input
        val expectedNames: List<String> = ["renamed/greeting.txt"]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
        assertEquals("hello", zipFile.readZipEntry("renamed/greeting.txt").text)
    }

    @Test
    fun `writes directory inputs recursively, including directory entries`() {
        inputsDir.createTextFile("root.txt")
        inputsDir.createTextFile("sub/nested.txt")
        inputsDir.createTextFile("sub/deeper/deepest.txt")

        zipFile.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path(""))])

        // the root directory itself is not registered as an entry
        val expectedNames: List<String> = [
            "root.txt",
            "sub/",
            "sub/deeper/",
            "sub/deeper/deepest.txt",
            "sub/nested.txt",
        ]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
        assertEquals("content of sub/deeper/deepest.txt", zipFile.readZipEntry("sub/deeper/deepest.txt").text)
    }

    @Test
    fun `nests directory inputs under their destination path`() {
        inputsDir.createTextFile("sub/nested.txt")

        zipFile.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path("dest/dir"))])

        val expectedNames: List<String> = [
            "dest/dir/",
            "dest/dir/sub/",
            "dest/dir/sub/nested.txt",
        ]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
    }

    @Test
    fun `uses forward slashes in entry names even for multi-segment destination paths`() {
        val file = inputsDir.createTextFile("hello.txt")

        // Path("a/b/c.txt") uses the platform separator internally, the zip entry must use '/' regardless
        zipFile.writeZip([ZipInput(path = file, destPathInArchive = Path("a/b/c.txt"))])

        val expectedNames: List<String> = ["a/b/c.txt"]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
    }

    @Test
    fun `writes an entry for empty directories`() {
        (inputsDir / "empty").createDirectories()

        zipFile.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path(""))])

        val expectedNames: List<String> = ["empty/"]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
        assertTrue(zipFile.readZipEntry("empty/").isDirectory, "'empty/' should be a directory entry")
    }

    @Test
    fun `excludes the manifest coming from the inputs`() {
        inputsDir.createTextFile("META-INF/MANIFEST.MF", text = "Manifest-Version: 1.0\n")
        inputsDir.createTextFile("META-INF/services/some.Service")

        zipFile.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path(""))])

        val expectedNames: List<String> = [
            "META-INF/",
            "META-INF/services/",
            "META-INF/services/some.Service",
        ]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
    }

    @Test
    fun `keeps manifest files that are not at the root of the archive`() {
        inputsDir.createTextFile("META-INF/MANIFEST.MF")

        zipFile.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path("nested"))])

        val expectedNames: List<String> = [
            "nested/",
            "nested/META-INF/",
            "nested/META-INF/MANIFEST.MF",
        ]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
    }

    @Test
    fun `sorts entries by name when reproducibleFileOrder is enabled`() {
        val fileB = inputsDir.createTextFile("b.txt")
        val fileA = inputsDir.createTextFile("a.txt")
        val fileC = inputsDir.createTextFile("c.txt")

        zipFile.writeZip(
            inputs = [
                ZipInput(path = fileB, destPathInArchive = Path("b.txt")),
                ZipInput(path = fileA, destPathInArchive = Path("a.txt")),
                ZipInput(path = fileC, destPathInArchive = Path("c.txt")),
            ],
            config = ZipConfig(reproducibleFileOrder = true),
        )

        val expectedNames: List<String> = ["a.txt", "b.txt", "c.txt"]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
    }

    @Test
    fun `keeps the input order when reproducibleFileOrder is disabled`() {
        val fileB = inputsDir.createTextFile("b.txt")
        val fileA = inputsDir.createTextFile("a.txt")
        val fileC = inputsDir.createTextFile("c.txt")

        zipFile.writeZip(
            inputs = [
                ZipInput(path = fileB, destPathInArchive = Path("b.txt")),
                ZipInput(path = fileA, destPathInArchive = Path("a.txt")),
                ZipInput(path = fileC, destPathInArchive = Path("c.txt")),
            ],
            config = ZipConfig(reproducibleFileOrder = false),
        )

        val expectedNames: List<String> = ["b.txt", "a.txt", "c.txt"]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
    }

    @Test
    fun `writes only the first input when several inputs map to the same entry name`() {
        val first = inputsDir.createTextFile("first/dup.txt", text = "first")
        val second = inputsDir.createTextFile("second/dup.txt", text = "second")

        zipFile.writeZip(
            [
                ZipInput(path = first, destPathInArchive = Path("dup.txt")),
                ZipInput(path = second, destPathInArchive = Path("dup.txt")),
            ]
        )

        val expectedNames: List<String> = ["dup.txt"]
        assertEquals(expectedNames, zipFile.readZipEntryNames())
        assertEquals("first", zipFile.readZipEntry("dup.txt").text)
    }

    @Test
    fun `uses a fixed timestamp for all entries by default`() {
        inputsDir.createTextFile("sub/nested.txt")

        zipFile.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path(""))])

        val entries = zipFile.readZipEntries()
        assertTrue(entries.isNotEmpty(), "the archive should not be empty")
        entries.forEach { entry ->
            assertEquals(FixedFileTime, entry.lastModifiedTime, "unexpected last modified time for '${entry.name}'")
            assertEquals(FixedFileTime, entry.lastAccessTime, "unexpected last access time for '${entry.name}'")
            assertEquals(FixedFileTime, entry.creationTime, "unexpected creation time for '${entry.name}'")
        }
    }

    @Test
    fun `preserves the file timestamps when configured to`() {
        val fileTime = FileTime.from(Instant.parse("2001-02-03T04:05:06Z")) // whole seconds, as stored in zip files
        val file = inputsDir.createTextFile("hello.txt", lastModifiedTime = fileTime)

        zipFile.writeZip(
            inputs = [ZipInput(path = file, destPathInArchive = Path("hello.txt"))],
            config = ZipConfig(preserveFileTimestamps = true),
        )

        assertEquals(fileTime, zipFile.readZipEntry("hello.txt").lastModifiedTime)
    }

    @Test
    fun `compresses all entries by default`() {
        val file = inputsDir.createTextFile("lib/some.jar", text = "a".repeat(1000))

        zipFile.writeZip([ZipInput(path = file, destPathInArchive = Path("lib/some.jar"))])

        assertEquals(DEFLATED, zipFile.readZipEntry("lib/some.jar").method)
    }

    @Test
    fun `stores all file entries uncompressed with the StoreAll strategy`() {
        inputsDir.createTextFile("classes/A.class", text = "a".repeat(1000))
        inputsDir.createTextFile("lib/some.jar", text = "b".repeat(1000))

        zipFile.writeZip(
            inputs = [ZipInput(path = inputsDir, destPathInArchive = Path(""))],
            config = ZipConfig(compressionStrategy = CompressionStrategy.StoreAll),
        )

        zipFile.readZipEntries().filterNot { it.isDirectory }.forEach { entry ->
            assertEquals(STORED, entry.method, "entry '${entry.name}' should be stored uncompressed")
            assertEquals(1000L, entry.size, "unexpected stored size for entry '${entry.name}'")
        }
    }

    @Test
    fun `stores only matching entries uncompressed with the Selective strategy`() {
        inputsDir.createTextFile("classes/A.class", text = "a".repeat(1000))
        inputsDir.createTextFile("lib/some.jar", text = "b".repeat(1000))
        inputsDir.createTextFile("lib/nested/other.jar", text = "c".repeat(1000))

        zipFile.writeZip(
            inputs = [ZipInput(path = inputsDir, destPathInArchive = Path(""))],
            // the patterns are regexes matched against the whole entry name
            config = ZipConfig(compressionStrategy = CompressionStrategy.Selective(uncompressedPatterns = ["lib/.*"])),
        )

        assertEquals(DEFLATED, zipFile.readZipEntry("classes/A.class").method)
        assertEquals(STORED, zipFile.readZipEntry("lib/some.jar").method)
        assertEquals(STORED, zipFile.readZipEntry("lib/nested/other.jar").method)
    }

    @Test
    fun `stores the uncompressed contents intact`() {
        val text = "some text that is long enough to be worth compressing".repeat(50)
        val file = inputsDir.createTextFile("lib/some.jar", text = text)

        zipFile.writeZip(
            inputs = [ZipInput(path = file, destPathInArchive = Path("lib/some.jar"))],
            config = ZipConfig(compressionStrategy = CompressionStrategy.StoreAll),
        )

        // reading via ZipFile checks the CRC of the entry, which is computed by hand for STORED entries
        assertEquals(text, zipFile.readZipEntry("lib/some.jar").text)
    }

    @Test
    fun `produces byte-identical archives despite different file timestamps`() {
        inputsDir.createTextFile("a.txt")
        inputsDir.createTextFile("sub/b.txt")

        val firstZip = tempDir / "first.zip"
        firstZip.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path(""))])

        inputsDir.createTextFile("a.txt", lastModifiedTime = FileTime.from(Instant.parse("2030-01-01T00:00:00Z")))
        inputsDir.createTextFile("sub/b.txt", lastModifiedTime = FileTime.from(Instant.parse("2031-01-01T00:00:00Z")))

        val secondZip = tempDir / "second.zip"
        secondZip.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path(""))])

        assertContentEquals(firstZip.readBytes(), secondZip.readBytes())
    }

    @Test
    fun `produces byte-identical archives despite different input order`() {
        val fileA = inputsDir.createTextFile("a.txt")
        val fileB = inputsDir.createTextFile("b.txt")

        val firstZip = tempDir / "first.zip"
        firstZip.writeZip(
            [
                ZipInput(path = fileA, destPathInArchive = Path("a.txt")),
                ZipInput(path = fileB, destPathInArchive = Path("b.txt")),
            ]
        )

        val secondZip = tempDir / "second.zip"
        secondZip.writeZip(
            [
                ZipInput(path = fileB, destPathInArchive = Path("b.txt")),
                ZipInput(path = fileA, destPathInArchive = Path("a.txt")),
            ]
        )

        assertContentEquals(firstZip.readBytes(), secondZip.readBytes())
    }

    @Test
    fun `writes an empty archive for an empty input list`() {
        zipFile.writeZip([])

        val expectedNames: List<String> = []
        assertEquals(expectedNames, zipFile.readZipEntryNames())
    }

    @Test
    fun `writes no content for directory entries`() {
        (inputsDir / "empty").createDirectories()

        zipFile.writeZip([ZipInput(path = inputsDir, destPathInArchive = Path(""))])

        val entry = zipFile.readZipEntry("empty/")
        assertEquals(0L, entry.size)
        assertNull(entry.text)
    }
}
