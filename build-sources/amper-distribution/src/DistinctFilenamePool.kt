/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.stdlib.hashing.sha256String
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

/**
 * Maps these [Path]s to their [real paths][Path.toRealPath], removing duplicates but preserving order.
 *
 * Among equal real paths, only the first one will be present in the resulting list.
 */
internal fun List<Path>.uniqueRealPaths(): List<Path> = map { it.toRealPath() }.distinct()

/**
 * Provides a way to track already seen filenames and generate unique filenames to avoid collisions.
 * See [registerAndGetName].
 */
internal class DistinctFilenamePool {

    private val usedNames = mutableSetOf<String>()
    private val namesByPath = mutableMapOf<Path, String>()

    /**
     * Adds the given [path] to the pool and returns a unique filename for this file.
     *
     * If no previously registered path had the same filename, the given path's filename is returned as-is.
     *
     * If the filename was already registered via a different path, a unique name is generated with the format:
     * `<originalFilename>-<generatedSuffix>.<originalExtension>`
     *
     * The same path always returns the same unique name for a given instance of [DistinctFilenamePool].
     * Different paths pointing to the same file (e.g. via symlinks) might return different names.
     */
    fun registerAndGetName(path: Path): String = namesByPath.getOrPut(path) {
        usedNames.addAndGet(path.name)
            ?: usedNames.addAndGet(path.uniqueFilename())
            ?: error("Path $path already seen")
    }

    /**
     * Lists the unique names for all registered files so far, respecting the order of registration.
     */
    fun list(): List<String> = usedNames.toList()

    private fun <T> MutableSet<T>.addAndGet(value: T): T? = if (add(value)) value else null

    /**
     * Returns a filename that is unique for this path, by inserting a hash of the entire path in the original filename.
     *
     * The original filename prefix is preserved, as well as the file extension.
     */
    private fun Path.uniqueFilename(): String = "$nameWithoutExtension-${pathString.sha256String().take(8)}.$extension"
}
