/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.dependency.resolution.files.fileOperationWithRetry
import org.jetbrains.amper.jar.copyZipDirectory
import org.jetbrains.amper.jar.nestedDirectories
import org.jetbrains.amper.jar.withZipEntry
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.jar.JarFile
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
        withZipEntry(it, entryPath) { stream -> block(stream) }
    }
}

/**
 * It takes a subfolder with the path <code>jarEntryDir<code> from given <code>srcJar<code>,
 *     copy its content right into the root of a new Jar archive,
 *     and then writes resulted Jar archive to the given FileChannel.
 */
@Throws(Exception::class)
suspend fun copyJarEntryDirToJar(fileChannel: FileChannel, jarEntryDir: String, srcJar: Path) {
    withContext(Dispatchers.IO) {
        // there is no need to close the output stream
        // since it is channel-based, and the channel itself is closed on the caller side
        copyZipDirectory(srcJar, jarEntryDir, Channels.newOutputStream(fileChannel))
    }
}

fun Path.toJarFile() = JarFile(toFile())

suspend fun getNestedDirectories(srcJar: Path, directory: String? = null): Set<String> =
    withContext(Dispatchers.IO) {
        nestedDirectories(srcJar, directory)
    }
