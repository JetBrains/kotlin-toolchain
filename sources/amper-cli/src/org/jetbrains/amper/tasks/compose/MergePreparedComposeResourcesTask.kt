/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.artifacts.PureArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier

class MergePreparedComposeResourcesTask(
    buildOutputRoot: AmperBuildOutputRoot,
    incrementalCache: IncrementalCache,
    private val fragment: LeafFragment,
) : PureArtifactTaskBase(buildOutputRoot, incrementalCache, "compose resources > merging") {
    private val packagingDir by extraInput(fragment.module.composeResourcesPackagingDir())

    private val preparedDirs by Selectors.fromFragmentWithDependencies(
        type = PreparedComposeResourcesDirArtifact::class,
        fragment = fragment,
        quantifier = Quantifier.AtLeastOne,
        // Only the fragments this compilation refines contribute here. The main fragments a test compilation
        // befriends are packaged by the main compilation, and a test fragment refines no main fragment, so resources
        // shared by both would be reported as an unresolvable conflict.
        dependencyType = FragmentDependencyType.REFINE,
    )

    private val mergedPreparedDir by MergedPreparedComposeResourcesDirArtifact(
        buildOutputRoot, module = fragment.module, platform = fragment.platform, isTest = fragment.isTest,
    )

    override suspend fun run(executionContext: TaskGraphExecutionContext) {
        packageComposeResourcesHierarchy(
            fragments = preparedDirs.fragmentComposeResources(),
            outputDir = mergedPreparedDir.path,
            packagingDir = packagingDir,
        )
    }
}
