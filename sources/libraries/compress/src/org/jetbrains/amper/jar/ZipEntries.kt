/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Opens the ZIP file corresponding to this [Path] for reading, and runs [block] on it before closing the file.
 *
 * Entry names and comments are decoded as UTF-8.
 */
inline fun <T> Path.useZipFile(block: (ZipFile) -> T): T = ZipFile(toFile()).use(block)

/**
 * Runs [block] on the content of the entry [entryPath] of this [ZipFile].
 *
 * Returns `null` if there is no such entry, and the result of [block] otherwise.
 */
inline fun <T> ZipFile.useZipEntryStream(entryPath: String, block: (InputStream) -> T): T? {
    val entry = getEntry(entryPath) ?: return null
    return getInputStream(entry).use(block)
}

/**
 * Writes the entries from the directory at the given [directoryPath] in this [ZipFile] to the given ZIP [output].
 *
 * The [directoryPath] prefix is removed from the entry names, which means that the entry hierarchy under the source
 * [directoryPath] is replicated under the root of the archive in the [output] stream.
 * Entries with the same name are written once.
 *
 * The entries keep their timestamps, comments and extra fields, so copying the same directory gives the same zip.
 * Only the entries under the specified [directoryPath] are read, through the central directory of this [ZipFile].
 *
 * The given [output] stream is the responsibility of the caller, so it is left open.
 */
fun ZipFile.copyZipDirectoryTo(directoryPath: String, output: ZipOutputStream) {
    val prefix = "$directoryPath/"
    val writtenNames = mutableSetOf<String>()
    for (entry in entries()) {
        val newName = entry.name.removePrefix(prefix)
        if (!entry.name.startsWith(prefix) || newName.isBlank() || !writtenNames.add(newName)) continue

        output.putNextEntry(entry.copyNamed(newName))
        if (!entry.isDirectory) {
            getInputStream(entry).use { it.copyTo(output) }
        }
        output.closeEntry()
    }
    output.finish()
}

/**
 * Returns the names of the direct subdirectories of the directory at [directoryPath] (relative to the root of this
 * [ZipFile]).
 *
 * Because [directoryPath] is relative to the root of the archive, passing the empty path or `"."` denotes the root
 * directory, thus top-level directory names are returned in this case.
 *
 * A subdirectory is returned even if it has no dedicated entry in the archive, as long as some entries exist inside it.
 */
fun ZipFile.listDirectSubdirectoriesOf(directoryPath: Path): Set<String> {
    require(!directoryPath.isAbsolute) { "directoryPath must be relative to the root of the archive" }

    val normalizedPath = directoryPath.normalize().invariantSeparatorsPathString
    val prefix = if (normalizedPath.isEmpty()) normalizedPath else "$normalizedPath/"

    return entries()
        .asSequence()
        .filter { it.name.startsWith(prefix) }
        .mapNotNullTo(mutableSetOf()) { it.toDirectSubdirectoryName(prefix) }
}

/**
 * Returns the name of the direct subdirectory of the directory denoted by [prefix] that this entry is inside,
 * or `null` if this entry is not inside a direct subdirectory of it.
 */
private fun ZipEntry.toDirectSubdirectoryName(prefix: String): String? {
    val relativeName = name.removePrefix(prefix)
    val childName = relativeName.substringBefore('/')
    return when {
        childName.isEmpty() -> null      // this entry is the directory itself
        '/' in relativeName -> childName // some entries exist inside the subdirectory
        isDirectory -> childName         // this entry is a dedicated directory entry of the subdirectory
        else -> null                     // this entry is a file directly inside the directory
    }
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
