/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.hashing

import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Performs a final update on this digest with the contents of the given [files], then completes the digest computation.
 *
 * That is, this function first calls [MessageDigest.update] with the input files, then calls [digest].
 *
 * If one of the [files] is a directory, the digest is updated with all the files in that directory, in alphabetical
 * order.
 */
fun MessageDigest.digest(files: List<Path>): ByteArray {
    update(files)
    return digest()
}

/**
 * Performs a final update on this digest with the contents of the given [file], then completes the digest computation.
 *
 * That is, this function first calls [MessageDigest.update] with the input file, then calls [digest].
 *
 * If [file] is a directory, the digest is updated with all the files in the directory, in alphabetical order.
 */
fun MessageDigest.digest(file: Path): ByteArray {
    update(file)
    return digest()
}

/**
 * Performs a final update on this digest with the contents of the given [stream], then completes the digest
 * computation.
 *
 * That is, this function first calls [MessageDigest.update] with the input file, then calls [digest].
 *
 * The caller is responsible for closing the stream.
 */
fun MessageDigest.digest(stream: InputStream): ByteArray {
    update(stream)
    return digest()
}

/**
 * Updates this digest using the contents of the given [files], in the order they appear in the list.
 *
 * If one of the elements is a directory, the digest is updated with all the files in that directory, in alphabetical
 * order.
 */
fun MessageDigest.update(files: List<Path>) {
    files.forEach { update(it) }
}

/**
 * Updates this digest using the contents of the given [file].
 *
 * If [file] is a directory, the digest is updated with all the files in the directory, in alphabetical order.
 */
fun MessageDigest.update(file: Path) {
    if (file.isDirectory()) {
        update(file.listDirectoryEntries().sortedBy { it.name })
    } else {
        file.inputStream().use { update(it) }
    }
}

/**
 * Updates this digest using all bytes from the given [InputStream].
 * The caller is responsible for closing the stream.
 */
fun MessageDigest.update(stream: InputStream) {
    val buffer = ByteArray(1024)
    var read = stream.read(buffer)
    while (read > -1) {
        update(buffer, 0, read)
        read = stream.read(buffer)
    }
}
