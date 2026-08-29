/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

/**
 * A snapshot of the interesting properties of an entry in an archive, read back from the written file.
 */
internal data class ZipEntrySnapshot(
    val name: String,
    val isDirectory: Boolean,
    val method: Int,
    val size: Long,
    val lastModifiedTime: FileTime,
    val creationTime: FileTime?,
    val lastAccessTime: FileTime?,
    val text: String?,
)

/**
 * Reads back all entries of the archive at this path, in the order in which they appear in the central directory
 * (which is the order in which they were written).
 *
 * Note that the creation and last access times are only present in the local headers of the entries (not in the central
 * directory), which is why the entries are read twice here: with [ZipFile] for the contents, and with [ZipInputStream]
 * for the local header timestamps.
 */
internal fun Path.readZipEntries(): List<ZipEntrySnapshot> {
    val localTimestamps = readLocalHeaderTimestamps()
    return ZipFile(toFile()).use { zip ->
        zip.entries().asSequence().map { entry ->
            val localEntry = localTimestamps[entry.name]
            ZipEntrySnapshot(
                name = entry.name,
                isDirectory = entry.isDirectory,
                method = entry.method,
                size = entry.size,
                lastModifiedTime = entry.lastModifiedTime,
                creationTime = localEntry?.creationTime,
                lastAccessTime = localEntry?.lastAccessTime,
                text = if (entry.isDirectory) null else zip.getInputStream(entry).use { it.readBytes().decodeToString() },
            )
        }.toList()
    }
}

private fun Path.readLocalHeaderTimestamps(): Map<String, ZipEntry> = ZipInputStream(inputStream().buffered()).use { zis ->
    generateSequence { zis.nextEntry }.associateBy { it.name }
}

/** Reads back the names of all entries of the archive at this path, in the order in which they were written. */
internal fun Path.readZipEntryNames(): List<String> = readZipEntries().map { it.name }

/** Reads back the entry at [entryName] in the archive at this path, failing if there is no such entry. */
internal fun Path.readZipEntry(entryName: String): ZipEntrySnapshot {
    val entries = readZipEntries()
    return entries.singleOrNull { it.name == entryName }
        ?: error("No entry '$entryName' in archive $this. Existing entries: ${entries.map { it.name }}")
}

/**
 * Creates a file at the given [relativePath] under this directory, with the given [text] as content, and optionally
 * the given [lastModifiedTime]. Missing parent directories are created as needed.
 */
internal fun Path.createTextFile(
    relativePath: String,
    text: String = "content of $relativePath",
    lastModifiedTime: FileTime? = null,
): Path {
    val file = resolve(relativePath)
    file.parent?.createDirectories()
    file.writeText(text)
    return if (lastModifiedTime != null) file.setLastModifiedTime(lastModifiedTime) else file
}
