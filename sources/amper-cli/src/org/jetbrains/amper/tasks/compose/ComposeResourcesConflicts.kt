/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.cli.userReadableError
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * Finds the files that more than one of the given [origins] would place at the very same path in the application
 * output, mapped to the origins providing them, sorted by path to keep the reporting stable. The paths serving as keys
 * of the resulting Map are relative to the directory of each origin.
 *
 * Note: Directories shared by several origins are merged and are not reported: this is what KGP does as well when it
 * merges the very same content with `DuplicatesStrategy.FAIL`. The very same directory reported more than once (the
 * same library may be reached through several task dependencies) contributes its files once and never conflicts.
 */
private fun <T : ComposeResourcesOrigin> findConflictingResources(origins: List<T>): Map<String, List<T>> = origins
    .distinctBy { it.dir }
    .flatMap { origin ->
        origin.dir.walk()
            .filter { it.isRegularFile() }
            .map { it.relativeTo(origin.dir).invariantSeparatorsPathString to origin }
    }
    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    .filterValues { it.size > 1 }
    .toSortedMap()

/**
 * The files that several of the given independent [origins] provide at the same path, mapped to the descriptions of
 * the origins providing them (sorted, to keep the reporting stable).
 */
internal fun findConflictingResourcesDescriptions(origins: List<ComposeResourcesOrigin>): Map<String, List<String>> =
    findConflictingResources(origins).mapValues { it.value.descriptions() }

/**
 * The files that several of the given [fragments] of a single module provide at the same path without any of them
 * refining all the others, mapped to the descriptions of the fragments providing them.
 *
 * The resources of a fragment override the ones of the fragments it refines, so such a file is only a conflict when
 * none of the fragments providing it is more specific than all the other ones.
 */
internal fun findUnresolvableOverrides(fragments: List<FragmentComposeResources>): Map<String, List<String>> =
    findConflictingResources(fragments)
        .filterValues { providers -> providers.none { it.overrides(providers) } }
        .mapValues { it.value.descriptions() }

private fun List<ComposeResourcesOrigin>.descriptions(): List<String> = map { it.description }.sorted()

/**
 * Fails the build if several of the given [origins] provide a file at the same path, listing every conflict.
 *
 * This is the merge policy aligned with KGP, which aggregates the KMP resources of the dependencies with the ones published
 * by the project itself using `DuplicatesStrategy.FAIL`. It is the same on every platform, only the place the
 * merged resources are packaged into differs.
 *
 * Compose resources are isolated by their package name, so a conflict normally means that several modules or
 * libraries share the same Compose resources package name.
 */
internal fun checkNoConflictingResources(origins: List<MergedComposeResources>) = reportConflictsIfAny(
    conflicts = findConflictingResourcesDescriptions(origins),
    packagedUnder = "$COMPOSE_RESOURCES_DIR/",
    problem = "The following resources are provided at the same path by several dependencies:",
    hint = "Compose resources are isolated by their package name, so this usually means that several modules or " +
            "libraries share it. Consider setting a unique `settings.compose.resources.packageName`.",
)

/**
 * Fails the build if several of the given [fragments] of a single module provide a file at the same path under
 * [packagedUnder] without any of them refining all the others.
 *
 * Only refinement lets a fragment override the resources of another one, so the resources of fragments that don't
 * refine each other cannot be merged: neither of them is the one to package.
 */
internal fun checkOverridesAreResolvable(
    fragments: List<FragmentComposeResources>,
    packagedUnder: String,
) = reportConflictsIfAny(
    conflicts = findUnresolvableOverrides(fragments),
    packagedUnder = packagedUnder,
    problem = "The following resources are provided by several fragments, none of which refines all the others:",
    hint = "Fragments that don't refine each other cannot override resources of each other. Declare such a resource in a " +
            "fragment refining all of them, or directly on a leaf-platform fragment",
)

/**
 * Fails the build if there are any [conflicts], reporting the [problem], every conflicting file (as packaged, that is
 * under [packagedUnder]) with the origins providing it, and the [hint] on how to fix it.
 */
private fun reportConflictsIfAny(
    conflicts: Map<String, List<String>>,
    packagedUnder: String,
    problem: String,
    hint: String,
) {
    if (conflicts.isEmpty()) return

    userReadableError {
        appendLine(problem)
        conflicts.forEach {
            appendLine("  '$packagedUnder${it.key}' is provided by ${it.value.joinToString(" and ")}")
        }
        append(hint)
    }
}
