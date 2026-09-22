/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.artifacts.JvmResourcesDirArtifact
import org.jetbrains.amper.tasks.artifacts.PureArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.compose.PreparedComposeResourcesDirArtifact
import org.jetbrains.amper.tasks.compose.composeResourcesPackagingDir
import org.jetbrains.amper.tasks.compose.fragmentComposeResources
import org.jetbrains.amper.tasks.compose.packageComposeResourcesHierarchy

/**
 * Provides the prepared Compose Resources of a single JVM compilation as java resources, to be placed into the
 * classpath.
 *
 * **Output**: [JvmResourcesDirArtifact]
 */
internal class JvmComposeResourcesTask(
    private val fragment: LeafFragment,
    private val buildOutputRoot: AmperBuildOutputRoot,
    incrementalCache: IncrementalCache,
) : PureArtifactTaskBase(buildOutputRoot, incrementalCache, "copying JVM compose resources") {
    private val packagingDir by extraInput(fragment.module.composeResourcesPackagingDir())

    private val preparedResources by Selectors.fromFragmentWithDependencies(
        type = PreparedComposeResourcesDirArtifact::class,
        fragment = fragment,
        quantifier = Quantifier.AtLeastOne,
        // Only the fragments this compilation refines contribute here. The main fragments a test compilation
        // befriends are packaged by the main compilation, whose output is on the test classpath already. Merging
        // them here would not just duplicate them: it would fail the build, because a test fragment refines no main
        // fragment, and resources of fragments that don't refine each other are reported as a conflict.
        dependencyType = FragmentDependencyType.REFINE,
    )

    private val outputJvmResources by JvmResourcesDirArtifact(
        buildOutputRoot = buildOutputRoot,
        fragment = fragment,
    )

    override suspend fun run(executionContext: TaskGraphExecutionContext) {
        packageComposeResourcesHierarchy(
            fragments = preparedResources.fragmentComposeResources(),
            outputDir = outputJvmResources.path,
            packagingDir = packagingDir,
        )
    }
}