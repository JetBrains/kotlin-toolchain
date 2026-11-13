/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.wrapper.AmperWrappers
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.writeText

@TaskAction
fun buildUnpackedDist(
    @Output outputDir: Path,
    @Input baseClasspath: Classpath,
    @Input extraClasspaths: Map<String, Classpath> = emptyMap(),
    @Input extraFilteredClasspaths: Map<String, FilteredClasspath> = emptyMap(),
    @Input thirdPartyStagingDir: Path,
) {
    outputDir.clean()

    (outputDir / "kotlin-cli.args").writeText(argFileContents())

    copyWithDeduplication(outputDir / "lib", baseClasspath.resolvedFiles.uniqueRealPaths())

    // The index and jars layout must match what is expected by org.jetbrains.amper.cli.lazyload.ExtraClasspath.
    generateDeduplicatedClasspaths(
        classpaths = extraClasspaths.map { NamedClasspath(it.key, it.value.resolvedFiles.uniqueRealPaths()) } +
                extraFilteredClasspaths.map { NamedClasspath(it.key, it.value.resolvedFiles.uniqueRealPaths()) },
        indexesDir = outputDir.resolve("extra"), // in sync with org.jetbrains.amper.cli.lazyload.ExtraClasspath
        jarsDir = outputDir.resolve("extra/jars"), // in sync with org.jetbrains.amper.cli.lazyload.ExtraClasspath
    )

    AmperWrappers.generateLaunchers(outputDir / "bin")

    thirdPartyStagingDir.copyToRecursively(
        target = outputDir,
        followLinks = false,
        overwrite = false,
    )
}

private val FilteredClasspath.resolvedFiles: List<Path>
    get() = classpath.resolvedFiles.filter { shouldInclude(it) }

private fun FilteredClasspath.shouldInclude(path: Path): Boolean = includeIfFileNameContains.any { it in path.name }
