/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.cli.userReadableError
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

private val logger = LoggerFactory.getLogger("ComposeResourcesConflicts")

/**
 * The [COMPOSE_RESOURCES_DIR] directory of a dependency or of a module, packaged into the application under that
 * same name, with a human-readable [description] of where it comes from.
 */
internal class ComposeResourcesOrigin(
    val description: String,
    val dir: Path,
) {
    companion object {
        /**
         * The Compose resources of the KMP resources [archive] of an external dependency, unpacked into
         * [extractedDir], or null if the archive doesn't have any.
         *
         * Only the [COMPOSE_RESOURCES_DIR] directory of the archive is consumed: a KMP resources archive may declare
         * an arbitrary layout, and nothing but Compose resources is ours to package. This keeps a dependency from
         * shadowing the files of the application itself (the `index.html` of a wasm application, for instance).
         */
        fun ofExternalArchive(archive: Path, extractedDir: Path): ComposeResourcesOrigin? {
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
            return ComposeResourcesOrigin(
                description = "the KMP resources archive '${archive.name}'",
                dir = composeResourcesDir,
            )
        }

        /**
         * The Compose resources of the module named [moduleName], already merged into [mergedDir],
         * or null if the module and its module dependencies don't have any.
         */
        fun ofModule(moduleName: String, mergedDir: Path): ComposeResourcesOrigin? {
            val composeResourcesDir = mergedDir / COMPOSE_RESOURCES_DIR
            if (!composeResourcesDir.isDirectory()) return null
            return ComposeResourcesOrigin(
                description = "module '$moduleName'",
                dir = composeResourcesDir,
            )
        }
    }
}

/**
 * Finds the files that more than one of the given [origins] would place at the very same path in the application
 * output, mapped to the descriptions of the origins providing them (both sorted, to keep the reporting stable).
 * The paths are relative to the [COMPOSE_RESOURCES_DIR] directory of each origin.
 *
 * Directories shared by several origins are merged and are not reported: this is what KGP does as well when it
 * merges the very same content with `DuplicatesStrategy.FAIL`. The very same directory reported more than once (the
 * same library may be reached through several task dependencies) contributes its files once and never conflicts.
 */
internal fun findConflictingResources(origins: List<ComposeResourcesOrigin>): Map<String, List<String>> = origins
    .distinctBy { it.dir }
    .flatMap { origin ->
        origin.dir.walk()
            .filter { it.isRegularFile() }
            .map { it.relativeTo(origin.dir).invariantSeparatorsPathString to origin.description }
    }
    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    .filterValues { it.size > 1 }
    .toSortedMap()
    .mapValues { it.value.sorted() }

/**
 * Fails the build if several of the given [origins] provide a file at the same path, listing every collision.
 *
 * This is the merge policy of KGP, which aggregates the KMP resources of the dependencies with the ones published
 * by the project itself using `DuplicatesStrategy.FAIL`. It is the same on every platform, only the place the
 * merged resources are packaged into differs.
 *
 * Compose resources are isolated by their package name, so a collision normally means that several modules or
 * libraries share the same Compose resources package name.
 */
internal fun checkNoConflictingResources(origins: List<ComposeResourcesOrigin>) {
    val conflicts = findConflictingResources(origins)
    if (conflicts.isEmpty()) return

    userReadableError {
        appendLine("The following resources are provided at the same path by several dependencies:")
        conflicts.forEach {
            appendLine("  '$COMPOSE_RESOURCES_DIR/${it.key}' is provided by ${it.value.joinToString(" and ")}")
        }
        append(
            "Compose resources are isolated by their package name, so this usually means that several modules or " +
                    "libraries share it. Consider setting a unique `settings.compose.resources.packageName`."
        )
    }
}
