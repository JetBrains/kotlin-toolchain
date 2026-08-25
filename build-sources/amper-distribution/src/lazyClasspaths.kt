/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.stdlib.hashing.sha256String
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createParentDirectories
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString
import kotlin.io.path.writeText

/**
 * The directory of the distribution that contains the jars and indices of the lazily-loaded extra classpaths.
 */
private const val EXTRA_DIR_NAME = "extra"

/**
 * The directory of the distribution that contains the jars of all lazily-loaded extra classpaths.
 */
private const val EXTRA_JARS_DIR_NAME = "$EXTRA_DIR_NAME/jars"

/**
 * The layout of the lazily-loaded extra classpaths in the distribution.
 *
 * The jars of all extra classpaths are deduplicated and placed in a single [EXTRA_JARS_DIR_NAME] directory, and each
 * classpath is described by an index file listing the names of the jars it consists of, in classpath order.
 *
 * This layout is read at runtime by the `ExtraClasspath` enum of the Amper CLI, so both must be kept in sync.
 */
internal class LazyClasspathsLayout(
    /**
     * The jars to place in the distribution.
     */
    val jars: List<JarToCopy>,
    /**
     * The classpath index files to create.
     */
    val indexFiles: List<TextFileToCreate>,
)

internal class JarToCopy(
    /**
     * The path where the original JAR is located.
     */
    val sourcePath: Path,
    /**
     * The path where the JAR should be copied, relative to the distribution root.
     */
    val destPathInDist: String,
)

internal class TextFileToCreate(
    /**
     * The contents that should be written to the file.
     */
    val contents: String,
    /**
     * The path where this file should be created, relative to the distribution root.
     */
    val destPathInDist: String,
)

internal fun lazyClasspathsLayout(
    extraClasspaths: Map<String, Classpath>,
    extraFilteredClasspaths: Map<String, FilteredClasspath>,
): LazyClasspathsLayout = lazyClasspathsLayout(
    classpaths = extraClasspaths.mapValues { [_, classpath] -> classpath.resolvedFiles } +
            extraFilteredClasspaths.mapValues { [_, classpath] -> classpath.resolvedFiles },
)

private fun lazyClasspathsLayout(classpaths: Map<String, List<Path>>): LazyClasspathsLayout {
    val jarNamesByPath = mutableMapOf<Path, String>()
    val jarNames = DistinctFilenamePool()
    // the same jar can appear in multiple classpaths, but we only want to copy it once
    for (jar in classpaths.values.flatten().distinct()) {
        jarNamesByPath[jar] = jarNames.registerAndGetName(jar)
    }
    return LazyClasspathsLayout(
        jars = jarNamesByPath.map { [path, jarName] ->
            JarToCopy(
                sourcePath = path,
                destPathInDist = "$EXTRA_JARS_DIR_NAME/$jarName",
            )
        },
        indexFiles = classpaths.entries.map { [name, jars] ->
            TextFileToCreate(
                contents = jars.distinct().joinToString("") { "${jarNamesByPath.getValue(it)}\n" },
                destPathInDist = "$EXTRA_DIR_NAME/$name.classpath.txt",
            )
        },
    )
}

/**
 * Copies the jars and writes the classpath index files of this layout into the distribution rooted at [distRoot].
 */
internal fun LazyClasspathsLayout.copyTo(distRoot: Path) {
    jars.forEach { (sourcePath, destPathInDist) ->
        sourcePath.copyTo(distRoot.resolve(destPathInDist).createParentDirectories())
    }
    indexFiles.forEach { (contents, destPathInDist) ->
        distRoot.resolve(destPathInDist).createParentDirectories().writeText(contents)
    }
}

internal class DistinctFilenamePool {

    private val usedNames = mutableSetOf<String>()

    /**
     * Adds the given [path] to the pool and returns a unique filename for this file.
     * The same path always returns the same unique name.
     *
     * The unique name is guaranteed to preserve the original filename (without extension) as prefix, as well as the
     * file extension. It may only contain extra text before the extension.
     */
    fun registerAndGetName(path: Path): String = usedNames.addAndGet(path.name)
        ?: usedNames.addAndGet(path.uniqueFilename())
        ?: error("Path $path already seen")

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
