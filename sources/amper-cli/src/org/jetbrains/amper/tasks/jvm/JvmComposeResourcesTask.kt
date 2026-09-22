/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.artifacts.JvmResourcesDirArtifact
import org.jetbrains.amper.tasks.artifacts.PureArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.compose.MergedPreparedComposeResourcesDirArtifact
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory

/**
 * Provides the merged Compose Resources of a single JVM compilation as java resources, to be placed into the
 * classpath. This is where the Compose resources runtime reads them from on the JVM.
 *
 * Contrary to the other platforms, this also runs for test compilations: a test fragment may declare its own
 * resources, and the JVM test classpath is where they belong.
 *
 * **Output**: [JvmResourcesDirArtifact]
 */
internal class JvmComposeResourcesTask(
    private val fragment: LeafFragment,
    private val buildOutputRoot: AmperBuildOutputRoot,
    incrementalCache: IncrementalCache,
) : PureArtifactTaskBase(buildOutputRoot, incrementalCache, "copying JVM compose resources") {
    private val mergedResources by Selectors.fromModuleOnly(
        type = MergedPreparedComposeResourcesDirArtifact::class,
        module = fragment.module,
        isTest = fragment.isTest,
        platform = fragment.platform,
    )

    private val outputJvmResources by JvmResourcesDirArtifact(
        buildOutputRoot = buildOutputRoot,
        fragment = fragment,
    )

    override suspend fun run(executionContext: TaskGraphExecutionContext) {
        // A compilation whose fragments declare no Compose resources has nothing merged, and so nothing to place on
        // the classpath. The output directory is cleaned before this runs, so there is nothing to remove either.
        val mergedDir = mergedResources.singleOrNull()?.path?.takeIf { it.isDirectory() } ?: return

        BuildPrimitives.copy(
            from = mergedDir,
            to = outputJvmResources.path.createDirectories(),
        )
    }
}