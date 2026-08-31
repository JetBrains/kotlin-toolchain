/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jar.ZipConfig
import org.jetbrains.amper.jar.ZipInput
import org.jetbrains.amper.jar.writeZip
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.mavenFileName
import org.jetbrains.amper.tasks.metadata.KMP_RESOURCES_CLASSIFIER
import org.jetbrains.amper.tasks.metadata.KMP_RESOURCES_EXTENSION
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/**
 * Archives the Compose resources of this module for the given [platform] into a KMP resources zip, so that they can be
 * published alongside the platform-specific artifact and picked up by the consumers of this library.
 *
 * The layout of the archive is the same as the one expected by the runtime library at the consumer side: the resources
 * are placed under `composeResources/<packageName>/` in the root of the archive.
 *
 * Note: only the resources of this very module are archived, as each library publishes its own resources.
 */
class ComposeResourcesArchiveTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val platform: Platform,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
) : ArtifactTaskBase() {
    private val mergedResources by Selectors.fromModuleOnly(
        type = MergedPreparedComposeResourcesDirArtifact::class,
        module = module,
        isTest = false,
        platform = platform,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val mergedDir = mergedResources.singleOrNull()?.path?.takeIf { it.isDirectory() }
        // Modules without any Compose resources have nothing to publish, and we don't want to publish empty archives.
        if (mergedDir == null || mergedDir.walk().none { it.isRegularFile() }) {
            return EmptyTaskResult
        }

        // The archive is named the way it is published, so that it can be published as-is.
        val archivePath = taskOutputRoot.path /
                module.publicationCoordinates(platform)
                    .copy(classifier = KMP_RESOURCES_CLASSIFIER)
                    .mavenFileName(KMP_RESOURCES_EXTENSION)

        incrementalCache.execute(
            key = taskName.id.value,
            inputValues = emptyMap(),
            inputFiles = listOf(mergedDir),
        ) {
            archivePath.deleteIfExists()
            archivePath.createParentDirectories()
            archivePath.writeZip(inputs = [ZipInput(path = mergedDir, destPathInArchive = Path(""))], ZipConfig())
            IncrementalCache.ExecutionResult([archivePath])
        }

        return Result(platform = platform, archivePath = archivePath)
    }

    class Result(
        val platform: Platform,
        val archivePath: Path,
    ) : TaskResult
}
