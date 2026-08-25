/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.rootFragment
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

private val AmperModule.commonSettings get() = rootFragment.settings

/**
 * The directory the Compose resources are packaged into, both by us and by any Compose Multiplatform
 * library publishing them as KMP resources.
 */
internal const val COMPOSE_RESOURCES_DIR = "composeResources"

// TODO Fix that with new frontend!
internal fun isComposeEnabledFor(module: AmperModule) =
    module.commonSettings.compose.enabled

/**
 * The KMP resources archives published by the external dependencies, as resolved by the tasks we depend on.
 */
internal fun List<TaskResult>.kmpResourcesArchives(): List<Path> = this
    .filterIsInstance<ResolveExternalDependenciesTask.Result>()
    .flatMap { it.kmpResourcesArchives }
    .distinct()

/**
 * The Compose resources of the modules that already merged theirs, one origin per module: they are kept apart to be
 * able to report conflicts between them.
 */
internal fun List<MergedPreparedComposeResourcesDirArtifact>.moduleComposeResources(): List<ModuleComposeResources> =
    mapNotNull { ModuleComposeResources.of(moduleName = it.moduleName, mergedDir = it.path) }

/**
 * The Compose resources of these KMP resources archives of external dependencies, extracting each of them into the
 * cache. Archives that don't have any Compose resources contribute no origin.
 */
internal suspend fun List<Path>.externalComposeResources(
    userCacheRoot: AmperUserCacheRoot,
): List<ExternalLibraryComposeResources> = mapNotNull { archive ->
    ExternalLibraryComposeResources.of(
        archive = archive,
        extractedDir = extractFileToCacheLocation(archive, userCacheRoot),
    )
}

/**
 * Packages the Compose resources of all the given [origins] into the [COMPOSE_RESOURCES_DIR] directory of
 * [outputDir], which is where the generated accessors expect to find them at runtime, on every platform.
 *
 * Fails the build if several origins provide a file at the same path. Copying doesn't overwrite anything either, so
 * that a conflict [checkNoConflictingResources] could miss (a case-only difference in file names on a
 * case-insensitive filesystem, say) still fails the build instead of silently dropping one of the files. This is
 * what KGP does as well: it aggregates the KMP resources of the dependencies with the ones of the project itself
 * with `DuplicatesStrategy.FAIL`.
 */
internal suspend fun packageComposeResources(origins: List<MergedComposeResources>, outputDir: Path) {
    checkNoConflictingResources(origins)

    // the very same directory may be reached through several task dependencies, it is packaged once
    val distinctOrigins = origins.distinctBy { it.dir }
    if (distinctOrigins.isEmpty()) return

    val composeResourcesDir = (outputDir / COMPOSE_RESOURCES_DIR).createDirectories()
    distinctOrigins.forEach { origin ->
        BuildPrimitives.copy(from = origin.dir, to = composeResourcesDir)
    }
}

/**
 * The Compose resources of the fragments of a single module, prepared to be packaged.
 */
internal fun List<PreparedComposeResourcesDirArtifact>.fragmentComposeResources(): List<FragmentComposeResources> =
    mapNotNull { FragmentComposeResources.of(fragment = it.fragment, preparedDir = it.preparedPath) }

/**
 * Packages the Compose resources of the [fragments] of a single module into the [packagingDir] directory of
 * [outputDir].
 *
 * The resources of a fragment override the ones of the fragments it refines: `ios/composeResources/foo` overrides
 * `common/composeResources/foo`. Fragments that don't refine each other cannot override each other, though: a file
 * provided by several of them, none of which refines all the others, fails the build.
 */
internal suspend fun packageComposeResourcesHierarchy(
    fragments: List<FragmentComposeResources>,
    outputDir: Path,
    packagingDir: String,
) {
    checkOverridesAreResolvable(fragments, packagedUnder = packagingDir)

    if (fragments.isEmpty()) return

    val targetDir = (outputDir / packagingDir).createDirectories()
    // Copying the most common fragments first lets the most specific fragment providing a file be the last one to
    // write it. Fragments that don't refine each other end up in an arbitrary order, which is harmless: the check
    // above ruled out that any of them provides the same file as another one.
    fragments
        .sortedBy { it.refinedFragmentsCount }
        .forEach { fragment -> BuildPrimitives.copy(from = fragment.dir, to = targetDir, overwrite = true) }
}
