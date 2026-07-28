/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.stdlib.io.path

import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import kotlin.io.path.createFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * Returns a list of the entries in this directory optionally filtered by matching against the specified [glob] pattern,
 *  or an empty list if the path is `null` or is not an existing directory.
 *
 * @param glob the globbing pattern. The syntax is specified by the [FileSystem.getPathMatcher] method.
 * @param linkOptions arguments for the [isDirectory] check.
 *
 * @throws java.util.regex.PatternSyntaxException if the directory exists but the glob pattern is invalid.
 * @throws IOException If an I/O error occurs.
 *
 * @see listDirectoryEntries
 * @see isDirectory
 */
@Throws(IOException::class)
fun Path?.listDirectoryEntriesIfExistsOrEmpty(
    glob: String = "*",
    vararg linkOptions: LinkOption,
): List<Path> = this?.takeIf { it.isDirectory(*linkOptions) }?.listDirectoryEntries(glob).orEmpty()

/**
 * Creates a new and empty file specified by this path or does nothing if the file already exists.
 * Will throw an [IOException] when the file exists, but it's not a regular file (e.g., a directory).
 *
 * @param attributes an optional array of file attributes to set atomically when creating the file.
 *
 * @return [this] path.
 *
 * @see createFile
 * @see isRegularFile
 */
@Throws(IOException::class)
@IgnorableReturnValue
fun Path.createRegularFileIfNotExists(
    attributes: Array<out FileAttribute<*>> = [],
): Path = if (isRegularFile()) this else createFile(*attributes)
