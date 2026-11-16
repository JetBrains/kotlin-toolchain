/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.dependency.resolution.files.fileOperationWithRetry
import org.jetbrains.amper.jar.copyZipDirectoryTo
import org.jetbrains.amper.jar.listDirectSubdirectoriesOf
import org.jetbrains.amper.jar.useZipEntryStream
import org.jetbrains.amper.jar.useZipFile
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.zip.ZipOutputStream
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal suspend fun readJarEntry(jarPath: Path, entryPath: String) : String? =
    withJarEntry(jarPath, entryPath) { jarInputStream ->
        InputStreamReader(jarInputStream).use {
            it.readText()
        }
    }

internal suspend fun hasJarEntry(jarPath: Path, entryPath: String) : Boolean? =
    withJarEntry(jarPath, entryPath) { _ -> true }

/**
 * Find an entry with the given [entryPath] in the given [jarPath] and run the lambda [block] on it.
 *
 * @return null if the entry is not found, and result of the given [block] otherwise
 */
suspend fun <T> withJarEntry(jarPath: Path, entryPath: String, block: suspend (InputStream) -> T) : T? {
    contract {
        callsInPlace(block, InvocationKind.UNKNOWN)
//         returnsResultOf(block)
    }
    return fileOperationWithRetry(jarPath) {
        it.useZipFile { zipFile ->
            zipFile.useZipEntryStream(entryPath) { stream ->
                block(stream)
            }
        }
    }
}

/**
 * Takes a subfolder with the path [jarEntryDir] from the given [srcJar],
 * copies its content right into the root of a new JAR archive,
 * and then writes resulting JAR archive to the given [fileChannel].
 *
 * Reading [srcJar] is retried while it fails, e.g., because the jar is locked right after being downloaded.
 */
@Throws(Exception::class)
suspend fun copyJarEntryDirToJar(fileChannel: FileChannel, jarEntryDir: String, srcJar: Path) {
    fileOperationWithRetry(srcJar) {
        // A failed attempt could have written a part of the archive already
        fileChannel.truncate(0).position(0)
        // there is no need to close the output stream
        // since it is channel-based, and the channel itself is closed on the caller side
        it.useZipFile { zip ->
            zip.copyZipDirectoryTo(jarEntryDir, ZipOutputStream(Channels.newOutputStream(fileChannel)))
        }
    }
}

/**
 * Returns the names of the direct subdirectories of the directory at [directoryPath] (relative to the root of JAR file
 * at the given [jarPath]).
 *
 * Because [directoryPath] is relative to the root of the archive, passing the empty path or `"."` denotes the root
 * directory, thus top-level directory names are returned in this case.
 */
suspend fun getNestedDirectories(jarPath: Path, directoryPath: Path): Set<String> =
    withContext(Dispatchers.IO) {
        jarPath.useZipFile { zipFile ->
            zipFile.listDirectSubdirectoriesOf(directoryPath)
        }
    }
