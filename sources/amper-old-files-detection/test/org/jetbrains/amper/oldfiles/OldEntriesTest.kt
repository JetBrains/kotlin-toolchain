/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.oldfiles

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileAttributesView
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class OldEntriesTest {
    private val oldInstant = Instant.parse("2026-01-10T00:00:00Z")
    private val recentInstant = Instant.parse("2026-10-01T00:00:00Z")
    private val cutoff = Instant.parse("2026-06-01T00:00:00Z")

    @TempDir
    lateinit var root: Path

    @Test
    fun `files not used since the cutoff are returned with their size and last used time`() {
        val oldFile = root / "old-file"
        oldFile.writeText("some old data")
        oldFile.setTimes(lastModified = oldInstant, lastAccess = oldInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff)

        assertEquals([oldFile], oldEntries.map { it.path })
        assertEquals("some old data".length.toLong(), oldEntries.single().sizeBytes)
        assertEquals(oldInstant, oldEntries.single().timestamp)
    }

    @Test
    fun `files used since the cutoff are not returned`() {
        val recentFile = root / "recent-file"
        recentFile.writeText("some recent data")
        recentFile.setTimes(lastModified = recentInstant, lastAccess = recentInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `a file accessed after the cutoff is considered used even if it was last modified before it`() {
        val readButNotModifiedFile = root / "read-but-not-modified"
        readButNotModifiedFile.writeText("some data")
        readButNotModifiedFile.setTimes(lastModified = oldInstant, lastAccess = recentInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `directories are considered used if any file inside was used after the cutoff`() {
        val dir = (root / "log-dir").createDirectories()
        (dir / "some-log").writeText("some log")
        (dir / "some-log").setTimes(lastModified = recentInstant, lastAccess = recentInstant)
        dir.setTimes(lastModified = oldInstant, lastAccess = oldInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `directories with everything unused are returned with the total size of their contents`() {
        val dir = (root / "old-dir").createDirectories()
        (dir / "file-a").writeText("1234") // 4 bytes
        (dir / "file-b").writeText("12") // 2 bytes
        dir.visitContents { it.setTimes(lastModified = oldInstant, lastAccess = oldInstant) }
        dir.setTimes(lastModified = oldInstant, lastAccess = oldInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff)

        assertEquals([dir], oldEntries.map { it.path })
        assertEquals(6, oldEntries.single().sizeBytes)
        assertEquals(oldInstant, oldEntries.single().timestamp)
    }

    @Test
    fun `files created before the cutoff are returned even if they were used after it`() {
        val oldButUsedFile = root / "old-but-used-file"
        oldButUsedFile.writeText("some old data")
        oldButUsedFile.setTimes(lastModified = recentInstant, lastAccess = recentInstant)
        oldButUsedFile.setCreationTime(oldInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff, indicator = OldIndicator.Created)

        assertEquals([oldButUsedFile], oldEntries.map { it.path })
        assertEquals("some old data".length.toLong(), oldEntries.single().sizeBytes)
        assertEquals(oldInstant, oldEntries.single().timestamp)
    }

    @Test
    fun `files created after the cutoff are not returned`() {
        val recentFile = root / "recent-file"
        recentFile.writeText("some recent data")
        recentFile.setTimes(lastModified = recentInstant, lastAccess = recentInstant)
        recentFile.setCreationTime(recentInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff, indicator = OldIndicator.Created)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `directories are considered new if anything inside was created after the cutoff`() {
        val dir = (root / "log-dir").createDirectories()
        (dir / "some-log").writeText("some log")
        (dir / "some-log").setTimes(lastModified = recentInstant, lastAccess = recentInstant)
        (dir / "some-log").setCreationTime(recentInstant)
        dir.setTimes(lastModified = oldInstant, lastAccess = oldInstant)
        dir.setCreationTime(oldInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff, indicator = OldIndicator.Created)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `directories with everything created before the cutoff are returned with the total size of their contents`() {
        val dir = (root / "old-dir").createDirectories()
        (dir / "file-a").writeText("1234") // 4 bytes
        (dir / "file-b").writeText("12") // 2 bytes
        dir.visitContents {
            it.setTimes(lastModified = oldInstant, lastAccess = oldInstant)
            it.setCreationTime(oldInstant)
        }
        dir.setTimes(lastModified = oldInstant, lastAccess = oldInstant)
        dir.setCreationTime(oldInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff, indicator = OldIndicator.Created)

        assertEquals([dir], oldEntries.map { it.path })
        assertEquals(6, oldEntries.single().sizeBytes)
        assertEquals(oldInstant, oldEntries.single().timestamp)
    }

    @Test
    fun `the last used time of a directory is the latest of all times inside it`() {
        val dir = (root / "old-dir").createDirectories()
        (dir / "file-a").writeText("1234")
        (dir / "file-a").setTimes(lastModified = oldInstant, lastAccess = oldInstant)
        (dir / "file-b").writeText("12")
        (dir / "file-b").setTimes(lastModified = recentInstant, lastAccess = oldInstant)
        dir.setTimes(lastModified = oldInstant, lastAccess = oldInstant)

        val oldEntries = root.listOldEntries(cutoff = cutoff)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `a missing root results in no old entries`() {
        val missingRoot = root / "missing"

        assertFalse(missingRoot.exists())

        val oldEntries = missingRoot.listOldEntries(cutoff = cutoff)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `an empty root results in no old entries`() {
        val oldEntries = root.listOldEntries(cutoff = cutoff)

        assertTrue(oldEntries.isEmpty())
    }

    @Test
    fun `a root that cannot be listed results in no old entries`() {
        val restrictedRoot = (root / "restricted").createDirectories()
        (restrictedRoot / "some-old-file").writeText("some old data")
        restrictedRoot.visitContents { it.setTimes(lastModified = oldInstant, lastAccess = oldInstant) }
        restrictedRoot.setTimes(lastModified = oldInstant, lastAccess = oldInstant)
        try {
            val readableSet = restrictedRoot.toFile().setReadable(false)
            assumeTrue(readableSet, "File system doesn't support marking file as non-readable")
            assumeTrue(
                runCatching { restrictedRoot.listDirectoryEntries() }.isFailure,
                "Setting readable to false didn't restrict listing directory entries",
            )
            val oldEntries = restrictedRoot.listOldEntries(cutoff = cutoff)
            assertTrue(oldEntries.isEmpty())
        } finally {
            restrictedRoot.toFile().setReadable(/* readable = */ true, /* ownerOnly = */ false)
            restrictedRoot.toFile().setWritable(/* writable = */ true, /* ownerOnly = */ false)
        }
    }

    /**
     * Sets the last modified and last access time, skipping the test on systems where it can't be set.
     */
    private fun Path.setTimes(lastModified: Instant, lastAccess: Instant) {
        val lastModifiedFileTime = FileTime.from(lastModified.toJavaInstant())
        val lastAccessFileTime = FileTime.from(lastAccess.toJavaInstant())

        val fileAttributes = fileAttributesView<BasicFileAttributeView>()
        fileAttributes.setTimes(
            /* lastModifiedTime = */ lastModifiedFileTime,
            /* lastAccessTime = */ lastAccessFileTime,
            /* createTime = */ null,
        )

        val actualLastModifiedTime = getLastModifiedTime()
        assumeTrue(actualLastModifiedTime == lastModifiedFileTime, "The last modified time can't be set on this system")

        val actualLastAccessTime = fileAttributes.readAttributes().lastAccessTime()
        assumeTrue(actualLastAccessTime == lastAccessFileTime, "The last access time can't be set on this system")
    }

    /**
     * Sets the creation time of this file or directory, skipping the test on systems where it can't be set.
     */
    private fun Path.setCreationTime(time: Instant) {
        val fileTime = FileTime.from(time.toJavaInstant())
        val fileAttributes = fileAttributesView<BasicFileAttributeView>()
        fileAttributes.setTimes(
            /* lastModifiedTime = */ null,
            /* lastAccessTime = */ null,
            /* createTime = */ fileTime,
        )

        val actualTime = fileAttributes.readAttributes().creationTime()
        assumeTrue(actualTime == fileTime, "The creation time can't be set on this system")
    }

    private fun Path.visitContents(block: (Path) -> Unit) {
        listDirectoryEntries().forEach { entry ->
            if (entry.isDirectory()) {
                entry.visitContents(block)
            }
            block(entry)
        }
    }
}
