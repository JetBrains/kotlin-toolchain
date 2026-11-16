/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Runs [block] on the content of the entry [entryPath] of the zip file [zip].
 *
 * Returns `null` if there is no such entry, and the result of [block] otherwise.
 */
inline fun <T> withZipEntry(zip: Path, entryPath: String, block: (InputStream) -> T): T? =
    ZipFile(zip.toFile()).use { zipFile ->
        val entry = zipFile.getEntry(entryPath) ?: return null
        zipFile.getInputStream(entry).use(block)
    }

/**
 * Writes the entries of the directory [directory] of the zip file [source] into [output] as a new zip file,
 * with the [directory] prefix removed from their names. Entries with the same name are written once.
 *
 * The entries keep their timestamps, comments and extra fields, so copying the same directory gives the same zip.
 * Only the entries of [directory] are read, through the central directory of [source].
 *
 * [output] is left open.
 */
fun copyZipDirectory(source: Path, directory: String, output: OutputStream) {
    val prefix = "$directory/"
    // ZipOutputStream isn't closed, since closing it would close the output, which is owned by the caller
    val out = ZipOutputStream(output)
    ZipFile(source.toFile()).use { zip ->
        val writtenNames = mutableSetOf<String>()
        for (entry in zip.entries()) {
            val newName = entry.name.removePrefix(prefix)
            if (!entry.name.startsWith(prefix) || newName.isBlank() || !writtenNames.add(newName)) continue

            out.putNextEntry(entry.copyNamed(newName))
            if (!entry.isDirectory) {
                zip.getInputStream(entry).use { it.copyTo(out) }
            }
            out.closeEntry()
        }
    }
    out.finish()
}

/**
 * Returns the names of the direct subdirectories of the directory [directory] of the zip file [zip],
 * or of its root directories if [directory] is `null`.
 *
 * Only directories that have their own entries in the zip file are returned.
 */
fun nestedDirectories(zip: Path, directory: String? = null): Set<String> =
    ZipFile(zip.toFile()).use { zipFile ->
        val directoryEntries = zipFile.entries().asSequence().filter { it.isDirectory }
        if (directory == null) {
            directoryEntries
                .map { it.name.removeSuffix("/") }
                .filter { '/' !in it }
        } else {
            val prefix = if (directory.endsWith('/')) directory else "$directory/"
            directoryEntries
                .filter { it.name.startsWith(prefix) }
                .map { it.name.substring(prefix.length).trimEnd('/') }
                .filter { it.isNotBlank() && '/' !in it }
        }.toSet()
    }

/**
 * Returns a new entry named [name] with the attributes of this entry.
 */
private fun ZipEntry.copyNamed(name: String): ZipEntry = ZipEntry(name).also { copy ->
    lastModifiedTime?.let { copy.lastModifiedTime = it }
    lastAccessTime?.let { copy.lastAccessTime = it }
    creationTime?.let { copy.creationTime = it }
    copy.comment = comment
    copy.extra = extra
    when {
        isDirectory -> {
            copy.method = ZipEntry.STORED
            copy.size = 0
            copy.crc = 0
        }
        method == ZipEntry.STORED -> {
            copy.method = ZipEntry.STORED
            copy.size = size
            copy.crc = crc
        }
    }
}
