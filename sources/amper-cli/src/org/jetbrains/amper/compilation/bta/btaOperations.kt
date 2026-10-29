/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation.bta

import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.getToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.classpathSnapshottingOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmClasspathSnapshottingOperation.Companion.GRANULARITY
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmClasspathSnapshottingOperation.Companion.PARSE_INLINED_LOCAL_CLASSES
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * Creates an ABI snapshot of the given [classpath entry][entryPath], and writes the result to a file in the given
 * [outputDir]. It is safe to share the [outputDir] between modules.
 *
 * Currently, only JARs and directories of classes are supported for [entryPath].
 *
 * The snapshot is generated with the given [granularity]. See the [ClassSnapshotGranularity] docs for details about how
 * to choose.
 *
 * @return the path to the saved snapshot file
 */
@OptIn(ExperimentalBuildToolsApi::class)
context(incrementalCache: IncrementalCache)
internal suspend fun KotlinToolchains.BuildSession.makeClasspathEntrySnapshot(
    entryPath: Path,
    /**
     * A more meaningful name for the classpath entry. It doesn't have to make the entry unique (we use hashes of the
     * full path for this), but it's better if it helps to identify it visually among other snapshots.
     */
    entryMoniker: String = entryPath.name,
    outputDir: Path,
    granularity: ClassSnapshotGranularity,
    logger: KotlinLogger? = null,
): Path {
    val jvmToolchain = kotlinToolchains.getToolchain<JvmPlatformToolchain>()
    val compilerVersion = kotlinToolchains.getCompilerVersion()
    val uniqueName = "$entryMoniker-${entryPath.pathString.sha256String()}-$compilerVersion"
    val snapshotFilePath = outputDir / "$uniqueName.abi"
    incrementalCache.executeForFiles(
        // This key is voluntarily not tied to the module we're about to compile, so it's reusable.
        // It has to contain the identity of the thing we're taking the snapshot of, because it serves for
        // synchronization with other modules (and state tracking).
        key = "kotlin-jvm-classpath-snapshotting-$uniqueName",
        inputValues = emptyMap(),
        inputFiles = listOf(entryPath),
    ) {
        spanBuilder("Snapshot classpath entry")
            .setAttribute("classpathEntry", entryPath.pathString)
            .use {
                val snapshot = spanBuilder("Compute snapshot data").use {
                    val snapshottingOperation = jvmToolchain.classpathSnapshottingOperation(entryPath) {
                        this[PARSE_INLINED_LOCAL_CLASSES] = true
                        this[GRANULARITY] = granularity
                    }
                    // The execution policy cannot be configured here at the moment (no matter what we pass, it's in-process)
                    executeOperation(snapshottingOperation, logger = logger)
                }
                spanBuilder("Save snapshot file")
                    .setAttribute("outputPath", snapshotFilePath.pathString)
                    .use {
                        snapshotFilePath.createParentDirectories()
                        snapshot.saveSnapshot(snapshotFilePath)
                    }
                listOf(snapshotFilePath)
            }
    }
    return snapshotFilePath
}
