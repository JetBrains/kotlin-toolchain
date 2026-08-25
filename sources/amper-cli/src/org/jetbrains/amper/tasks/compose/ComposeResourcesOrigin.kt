/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.allFragmentDependencies
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private val logger = LoggerFactory.getLogger("ComposeResourcesOrigin")

/**
 * Compose resources to package into an application, coming from a single origin.
 */
internal sealed interface ComposeResourcesOrigin {
    /**
     * Human-readable description of where these resources come from, to report conflicts with other origins.
     */
    val description: String

    /**
     * The directory holding the resources, laid out the way they are packaged.
     */
    val dir: Path
}

/**
 * The Compose resources of a whole library or module, whose own resources are already merged: the resources of the
 * fragments of a module by [MergePreparedComposeResourcesTask], the ones of a library by its publisher.
 *
 * Libraries and modules are independent of each other, so none of them may override the resources of another one.
 */
internal sealed interface MergedComposeResources : ComposeResourcesOrigin

/**
 * The Compose resources published by an external library as a KMP resources archive.
 */
internal class ExternalLibraryComposeResources private constructor(
    override val description: String,
    override val dir: Path,
) : MergedComposeResources {
    companion object {
        /**
         * The Compose resources of the KMP resources [archive] of an external library, unpacked into [extractedDir],
         * or null if the archive doesn't have any.
         *
         * Only the [COMPOSE_RESOURCES_DIR] directory of the archive is consumed: a KMP resources archive may declare
         * an arbitrary layout, and nothing but Compose resources is ours to package. This keeps a library from
         * shadowing the files of the application itself (the `index.html` of a wasm application, for instance).
         */
        fun of(archive: Path, extractedDir: Path): ExternalLibraryComposeResources? {
            val composeResourcesDir = extractedDir / COMPOSE_RESOURCES_DIR
            if (!composeResourcesDir.isDirectory()) {
                // Compose Multiplatform libraries always publish their resources under this directory. A library
                // publishing KMP resources with a layout of its own is not something we know how to consume.
                logger.debug(
                    "The KMP resources archive '{}' has no '{}' directory, its contents are not packaged.",
                    archive.name,
                    COMPOSE_RESOURCES_DIR,
                )
                return null
            }
            return ExternalLibraryComposeResources(
                description = "the KMP resources archive '${archive.name}'",
                dir = composeResourcesDir,
            )
        }
    }
}

/**
 * The Compose resources of a module of this project, merged by [MergePreparedComposeResourcesTask] from the fragments
 * taking part in the compilation of a single leaf platform. A module built for several leaf platforms therefore has
 * one of these per platform.
 */
internal class ModuleComposeResources private constructor(
    override val description: String,
    override val dir: Path,
) : MergedComposeResources {
    companion object {
        /**
         * The Compose resources of the module named [moduleName], already merged into [mergedDir], or null if the
         * fragments merged into it don't declare any.
         *
         * The resources of the module dependencies are not a part of [mergedDir]: each of them is an origin of its
         * own, so that conflicts between independent modules can be reported.
         */
        fun of(moduleName: String, mergedDir: Path): ModuleComposeResources? {
            val composeResourcesDir = mergedDir / COMPOSE_RESOURCES_DIR
            if (!composeResourcesDir.isDirectory()) return null
            return ModuleComposeResources(
                description = "module '$moduleName'",
                dir = composeResourcesDir,
            )
        }
    }
}

/**
 * The Compose resources declared by a single fragment of a module, prepared to be packaged.
 *
 * Contrary to the [MergedComposeResources], the fragments of a module are not independent of each other: the
 * resources of a fragment override the ones of the fragments it refines.
 */
internal class FragmentComposeResources private constructor(
    private val fragmentName: String,
    private val refinedFragments: Set<String>,
    override val dir: Path,
) : ComposeResourcesOrigin {
    override val description: String
        get() = "fragment '$fragmentName'"

    /**
     * The number of fragments this fragment refines, directly or not.
     *
     * A fragment refines strictly more fragments than any of the fragments it refines, so this orders fragments from
     * the most common to the most specific one (fragments that don't refine each other in an arbitrary order).
     */
    val refinedFragmentsCount: Int
        get() = refinedFragments.size

    /**
     * Whether the resources of this fragment override the ones of all the [others], that is, whether this fragment
     * refines every one of them.
     */
    fun overrides(others: Iterable<FragmentComposeResources>): Boolean =
        others.all { it.fragmentName == fragmentName || it.fragmentName in refinedFragments }

    companion object {
        /**
         * The prepared Compose resources of the given [fragment], found in [preparedDir], or null if the fragment
         * doesn't declare any.
         */
        fun of(fragment: Fragment, preparedDir: Path): FragmentComposeResources? = of(
            fragmentName = fragment.name,
            // only refinement lets a fragment override the resources of another one
            refinedFragments = fragment.allFragmentDependencies(dependencyType = FragmentDependencyType.REFINE)
                .map { it.name }
                .toSet(),
            preparedDir = preparedDir,
        )

        /**
         * The prepared Compose resources of the fragment named [fragmentName], which refines the fragments named
         * [refinedFragments] (directly or not), found in [preparedDir], or null if the fragment doesn't declare any.
         *
         * Contrary to the other origins, [preparedDir] is the packaging directory itself: the resources of a
         * fragment are prepared right where they are packaged.
         */
        fun of(
            fragmentName: String,
            refinedFragments: Set<String>,
            preparedDir: Path,
        ): FragmentComposeResources? {
            if (!preparedDir.isDirectory()) return null
            return FragmentComposeResources(
                fragmentName = fragmentName,
                refinedFragments = refinedFragments,
                dir = preparedDir,
            )
        }
    }
}
