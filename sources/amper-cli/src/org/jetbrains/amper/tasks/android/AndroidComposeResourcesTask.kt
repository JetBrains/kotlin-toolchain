/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.compose.MergedPreparedComposeResourcesDirArtifact
import kotlin.io.path.exists

/**
 * Just passes through the prepared Compose Resources to be packaged as assets for Android.
 *
 * The resources of all the fragments taking part in the Android compilation are passed as a single, already merged
 * assets root: they override each other following the refinement relation, the same way they do on every other
 * platform.
 *
 * **Output**: [AdditionalAndroidAssetsProvider].
 *
 * @see AndroidAarTask
 */
class AndroidComposeResourcesTask(
    override val taskName: TaskName,
    fragment: LeafFragment,
) : ArtifactTaskBase() {
    init {
        // Assets are only packaged by the AAR, which is production-only, and Android test compilations don't even
        // get the AAR on their classpath (they get the production jar). There is nothing to prepare for them.
        require(!fragment.isTest) { "Compose resources are not packaged as assets for test compilations" }
    }

    private val mergedResources by Selectors.fromModuleOnly(
        type = MergedPreparedComposeResourcesDirArtifact::class,
        module = fragment.module,
        isTest = false,
        platform = fragment.platform,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        val mergedResourcesPath = mergedResources.singleOrNull()?.path

        return Result(
            assetsRoots = if (mergedResourcesPath != null && mergedResourcesPath.exists()) {
                [AdditionalAndroidAssetsProvider.AssetsRoot(path = mergedResourcesPath)]
            } else []
        )
    }

    private class Result(
        override val assetsRoots: List<AdditionalAndroidAssetsProvider.AssetsRoot>,
    ) : TaskResult, AdditionalAndroidAssetsProvider
}