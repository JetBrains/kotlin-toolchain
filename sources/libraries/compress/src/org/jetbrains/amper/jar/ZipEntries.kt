/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jar

import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs [block] on the content of the entry [entryPath] of the zip file [zip].
 *
 * Returns `null` if there is no such entry, and the result of [block] otherwise.
 */
inline fun <T> withZipEntry(zip: Path, entryPath: String, block: (InputStream) -> T): T? =
    JarFile(zip.toFile()).use { jarFile ->
        val entry = jarFile.getJarEntry(entryPath) ?: return null
        jarFile.getInputStream(entry).use(block)
    }

/**
 * Writes the entries of the directory [directory] of the zip file [source] into [output] as a new zip file,
 * with the [directory] prefix removed from their names. Entries with the same name are written once.
 *
 * [output] is left open.
 */
fun copyZipDirectory(source: Path, directory: String, output: OutputStream) {
    // ZipOutputStream isn't closed, since closing it would close the output, which is owned by the caller
    val out = ZipOutputStream(output)
    val buffer = ByteArray(4096)
    val writtenPaths = mutableSetOf<String>()
    JarInputStream(FileInputStream(source.toFile())).use { jarInputStream ->
        var entry: JarEntry? = jarInputStream.nextJarEntry
        while (entry != null) {
            if (entry.name.startsWith("$directory/")) {
                val newEntryName = entry.name.removePrefix("$directory/")
                if (!newEntryName.isBlank() && writtenPaths.add(newEntryName)) {
                    if (entry.isDirectory) {
                        out.addDirectoryEntry(newEntryName)
                    } else {
                        out.copyEntry(jarInputStream, entry, newEntryName, buffer)
                    }
                }
            }
            entry = jarInputStream.nextJarEntry
        }
        out.finish()
    }
}

/**
 * Returns the names of the direct subdirectories of the directory [directory] of the zip file [zip],
 * or of its root directories if [directory] is `null`.
 *
 * Only directories that have their own entries in the zip file are returned.
 */
fun nestedDirectories(zip: Path, directory: String? = null): Set<String> =
    JarFile(zip.toFile()).use { jarFile ->
        val directoryEntries = jarFile.entries().asSequence().filter { it.isDirectory }
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

private fun ZipOutputStream.copyEntry(
    input: JarInputStream,
    sourceEntry: ZipEntry,
    newEntryName: String,
    buffer: ByteArray,
) {
    val newEntry = JarEntry(newEntryName)
    if (sourceEntry.method == JarEntry.STORED) {
        newEntry.method = ZipEntry.STORED
        newEntry.size = sourceEntry.size
        newEntry.crc = sourceEntry.crc
    }
    newEntry.time = System.currentTimeMillis()
    newEntry.extra = sourceEntry.extra

    putNextEntry(newEntry)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        write(buffer, 0, read)
    }
    closeEntry()
}

private fun ZipOutputStream.addDirectoryEntry(relativePath: String) {
    val entry = ZipEntry(relativePath).apply {
        method = ZipEntry.STORED
        size = 0
        crc = 0
    }
    putNextEntry(entry)
    closeEntry()
}
