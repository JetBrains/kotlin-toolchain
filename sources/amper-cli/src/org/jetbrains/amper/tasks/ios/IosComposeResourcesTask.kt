/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.compose.MergedPreparedComposeResourcesDirArtifact
import org.jetbrains.amper.tasks.compose.externalComposeResources
import org.jetbrains.amper.tasks.compose.kmpResourcesArchives
import org.jetbrains.amper.tasks.compose.moduleComposeResources
import org.jetbrains.amper.tasks.compose.packageComposeResources
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.isDirectory

/**
 * Assembles all the required resources to be later packaged into the iOS app.
 */
class IosComposeResourcesTask(
    override val taskName: TaskName,
    private val leafFragment: LeafFragment,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    private val userCacheRoot: AmperUserCacheRoot,
) : ArtifactTaskBase() {
    private val dependenciesMerged by Selectors.fromModuleWithDependencies(
        type = MergedPreparedComposeResourcesDirArtifact::class,
        leafFragment = leafFragment,
        userCacheRoot = userCacheRoot,
        incrementalCache = incrementalCache,
        quantifier = Quantifier.AnyOrNone,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val mergedDirs = dependenciesMerged.filter { it.path.isDirectory() }
        val moduleOrigins = mergedDirs.moduleComposeResources()
        val externalArchives = dependenciesResult.kmpResourcesArchives()
        val outputPath = taskOutputRoot.path / "merged"
        if (mergedDirs.isEmpty() && externalArchives.isEmpty()) {
            outputPath.deleteRecursively()
            return EmptyTaskResult
        }

        incrementalCache.execute(
            key = taskName.id.value,
            inputValues = emptyMap(),
            inputFiles = mergedDirs.map { it.path } + externalArchives,
        ) {
            outputPath.clean()

            packageComposeResources(
                origins = externalArchives.externalComposeResources(userCacheRoot) + moduleOrigins,
                outputDir = outputPath,
            )
            IncrementalCache.ExecutionResult([outputPath])
        }

        return Result(composeResourcesDirectory = outputPath)
    }

    class Result(
        val composeResourcesDirectory: Path,
    ) : TaskResult
}
