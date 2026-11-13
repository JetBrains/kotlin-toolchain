/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal data class NamedClasspath(val name: String, val jars: List<Path>)

/**
 * Copies the given classpaths into the given [jarsDir], deduplicating common JARs between classpaths, and ensuring
 * that different JARs with identical names don't collide.
 *
 * All jars of all classpaths are guaranteed to be present in [jarsDir], but might have different names in case of
 * collision. The filename prefix and extension are guaranteed to be preserved.
 *
 * To distinguish jars from different classpath, an index for each [NamedClasspath] is generated in [indexesDir], with
 * the filename `<classpath-name>.classpath.txt`.
 * Each index is a text file containing the names of the jars as they appear in [jarsDir], one per line.
 */
internal fun generateDeduplicatedClasspaths(
    classpaths: List<NamedClasspath>,
    indexesDir: Path,
    jarsDir: Path,
) {
    val jarNamesByPath = generateUniqueNames(paths = classpaths.flatMap { it.jars })

    jarsDir.createDirectories()
    jarNamesByPath.forEach { [path, jarName] ->
        path.copyTo(jarsDir.resolve(jarName))
    }

    indexesDir.createDirectories()
    classpaths.forEach { (name, jars) ->
        val uniqueJarNames = jars.map { jarNamesByPath.getValue(it) }
        indexesDir.resolve("$name.classpath.txt").writeText(uniqueJarNames.joinToString("\n"))
    }
}

/**
 * Generates unique filenames corresponding to the given [paths].
 *
 * If some files have different paths but the same filename, a new filename is generated for some of them, preserving
 * the filename as extension.
 *
 * If [paths] contains duplicates (equal, or with the same [real path][java.nio.file.Path.toRealPath]), only the first
 * one will be present in the returned map, and its prefix will be that of the real filename.
 */
private fun generateUniqueNames(paths: List<Path>): Map<Path, String> {
    val pool = DistinctFilenamePool()
    return paths.associateWith { pool.registerAndGetName(it) }
}
