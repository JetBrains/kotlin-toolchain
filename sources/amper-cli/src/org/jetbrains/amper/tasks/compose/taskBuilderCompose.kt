/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.dependency.resolution.attributes.Usage
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.isPublishingEnabled
import org.jetbrains.amper.tasks.ModuleTaskTypes
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.getTaskOutputPath
import org.jetbrains.amper.tasks.TaskNameFactory
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.refinedLeafFragmentsDependingOn
import org.jetbrains.amper.tasks.rootFragment
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.walk

fun ProjectTasksBuilder.setupComposeTasks() {
    configureComposeResourcesGeneration()
}

private fun ProjectTasksBuilder.configureComposeResourcesGeneration() {
    allModules().withEach configureModule@ {
        if (!isComposeEnabledFor(module)) {
            return@configureModule
        }

        val rootFragment = module.rootFragment
        val config = rootFragment.settings.compose.resources
        val packageName = module.composeResourcesPackageName()
        val makeAccessorsPublic = config.exposedAccessors
        val resClassName = config.nameOfResClass
        val packagingDir = "$COMPOSE_RESOURCES_DIR/$packageName/"

        // `expect` is generated in `common` only, while `actual` are generated in the refined fragments.
        //  do not separate `expect`/`actual` if the module only contains a single main fragment.
        val [testFragments, mainFragments] = module.fragments.partition { it.isTest }
        val shouldSeparateExpectActual = mainFragments.size > 1 || testFragments.size > 1

        /*
         The tasks generate code (collectors and Res) if either is true:
          - The project has some actual resources in any of the fragments.
          - The user explicitly requested to make the resources API public.
            We generate public code to make API not depend on the actual presence of the resources,
            because the user already opted-in to their usage.
        */
        val shouldGenerateCode = makeAccessorsPublic || module.fragments.any {
            // TODO: Do we need this requirement here? Can we always generate this code?
            it.composeResourcesPath.isDirectory() && it.composeResourcesPath.walk().any { file -> !file.isHidden() }
        }

        // Configure "global" tasks that generate common code (into rootFragment).
        tasks.registerTask(
            GenerateResClassTask(
                buildOutputRoot = context.buildOutputRoot,
                incrementalCache = context.incrementalCache,
                rootFragment = rootFragment,
                makeAccessorsPublic = makeAccessorsPublic,
                packageName = packageName,
                packagingDir = packagingDir,
                shouldGenerateCode = shouldGenerateCode,
                resClassName = resClassName,
            )
        )
        if (shouldSeparateExpectActual) {
            tasks.registerTask(
                GenerateExpectResourceCollectorsTask(
                    buildOutputRoot = context.buildOutputRoot,
                    incrementalCache = context.incrementalCache,
                    rootFragment = rootFragment,
                    packageName = packageName,
                    makeAccessorsPublic = makeAccessorsPublic,
                    shouldGenerateCode = shouldGenerateCode,
                    resClassName = resClassName,
                )
            )
        }

        // Configure per-fragment tasks, as resources may reside in arbitrary fragments.
        module.fragments.forEach { fragment ->
            tasks.registerBuiltinArtifact(
                ComposeResourcesSourceDirArtifact(
                    buildOutputRoot = context.buildOutputRoot,
                    fragment = fragment,
                    conventionPath = fragment.composeResourcesPath,
                )
            )

            tasks.registerTask(
                PrepareComposeResourcesTask(
                    buildOutputRoot = context.buildOutputRoot,
                    incrementalCache = context.incrementalCache,
                    fragment = fragment,
                    packagingDir = packagingDir,
                )
            )
            tasks.registerTask(
                GenerateResourceAccessorsTask(
                    buildOutputRoot = context.buildOutputRoot,
                    incrementalCache = context.incrementalCache,
                    fragment = fragment,
                    packageName = packageName,
                    packagingDir = packagingDir,
                    makeAccessorsPublic = makeAccessorsPublic,
                    resClassName = resClassName,
            ))
        }

        // Configure tasks that generate code into the leaf-fragments
        refinedLeafFragmentsDependingOn(rootFragment).forEach { fragment ->
            tasks.registerTask(
                GenerateActualResourceCollectorsTask(
                    buildOutputRoot = context.buildOutputRoot,
                    incrementalCache = context.incrementalCache,
                    useActualModifier = shouldSeparateExpectActual,
                    fragment = fragment,
                    packageName = packageName,
                    makeAccessorsPublic = makeAccessorsPublic,
                    shouldGenerateCode = shouldGenerateCode,
                    resClassName = resClassName,
                )
            )
            tasks.registerTask(
                MergePreparedComposeResourcesTask(
                    buildOutputRoot = context.buildOutputRoot,
                    incrementalCache = context.incrementalCache,
                    fragment = fragment,
                    packagingDir = packagingDir,
                )
            )

            // Platforms that can't pack the resources into their main artifact publish them in a dedicated archive.
            val publishesKmpResources = module.isPublishingEnabled() &&
                    Usage.kmpResourcesUsage(fragment.platform.toResolutionPlatform()!!) != null
            if (publishesKmpResources) {
                val archiveTaskName = ComposeTaskType.ComposeResourcesArchive.getTaskName(module, fragment.platform)
                tasks.registerTask(
                    ComposeResourcesArchiveTask(
                        taskName = archiveTaskName,
                        module = module,
                        platform = fragment.platform,
                        taskOutputRoot = context.getTaskOutputPath(archiveTaskName),
                        incrementalCache = context.incrementalCache,
                    ),
                )
                tasks.registerDependency(
                    taskName = ModuleTaskTypes.PrepareMavenPublishables.getTaskName(module),
                    dependsOn = archiveTaskName,
                )
            }
        }
    }
}

internal enum class ComposeTaskType(
    override val internalName: String,
    override val operationMoniker: String,
) : TaskNameFactory.LeafPlatform {
    ComposeResourcesArchive("composeResourcesArchive", "compose resources > archiving"),
}
