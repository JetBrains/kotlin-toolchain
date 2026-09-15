/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.directSwiftPMDependencies
import org.jetbrains.amper.maven.publish.isMultiplatformPublication
import org.jetbrains.amper.swiftpm.SwiftPMDependency
import org.jetbrains.amper.swiftpm.SwiftPMImportMetadata

/**
 * The Maven classifier under which the SwiftPM metadata of a library is published.
 * It follows the KGP convention, e.g. `mylib-1.0-swiftpm-metadata.json`.
 */
internal const val SWIFTPM_METADATA_CLASSIFIER = "swiftpm-metadata"

/**
 * The file extension of the published SwiftPM metadata, following the KGP convention.
 */
internal const val SWIFTPM_METADATA_EXTENSION = "json"

/**
 * The SwiftPM metadata describing SwiftPM packages this module directly depends on
 * (`null` if this module declares no SwiftPM dependency).
 */
internal fun AmperModule.swiftPMImportMetadataForPublication(): SwiftPMImportMetadata? {
    // SwiftPM dependencies can only be declared for Apple platforms, which only multiplatform libraries support,
    // so this metadata is always part of the root publication, which only multiplatform publications have.
    if (!isMultiplatformPublication()) return null

    val dependencies = directSwiftPMDependencies().map { it.swiftPMDependency }.toSet()
    if (dependencies.isEmpty()) return null

    return SwiftPMImportMetadata(
        // Consumers use these to know which of their own targets this metadata applies to. They are KonanTarget names,
        // like in the KGP publication (not Kotlin Toolchain platform names).
        konanTargets = leafAppleFragments().map { it.platform.konanTargetName() }.toSet(),
        // Only deployment targets that the library declares explicitly belong here. KGP publishes null unless the
        // user sets them in its DSL and treats its own defaults as a consumer-side fallback. Consumers raise their
        // minimum to the maximum of the published values, so publishing our build-time defaults would silently bump
        // the minimum OS version of every consumer. Kotlin Toolchain has no DSL for these yet, so nothing to declare.
        iosDeploymentVersion = null,
        macosDeploymentVersion = null,
        watchosDeploymentVersion = null,
        tvosDeploymentVersion = null,
        isModulesDiscoveryEnabled = true,
        dependencies = dependencies,
    )
}

/**
 * Fails with a user-readable error if this module publishes dependencies on local Swift packages, because they cannot
 * be consumed from the repository with the given [targetRepositoryId].
 *
 * Local Swift packages are published as absolute paths (this is also what KGP does), so the consumers of the library
 * can only resolve them on the machine that published it. This is acceptable when publishing to the local Maven
 * repository, but not to repositories that are shared with other machines.
 */
internal fun AmperModule.checkNoPublishedLocalSwiftPackages(targetRepositoryId: String) {
    val localPackages = swiftPMImportMetadataForPublication()
        ?.dependencies
        ?.filterIsInstance<SwiftPMDependency.Local>()
        // sorted for a reproducible error message, the declaration order is irrelevant here
        ?.sortedBy { it.packageName }
        .orEmpty()
    if (localPackages.isEmpty()) return

    userReadableError {
        appendLine("Module '$userReadableName' cannot be published to the repository '$targetRepositoryId' because " +
                "it depends on the following local Swift packages:")
        localPackages.forEach {
            appendLine(" - ${it.packageName} (${it.absolutePath})")
        }
        appendLine("Local Swift packages are published as absolute paths, so the consumers of this library would " +
                "only be able to resolve them on this machine.")
        append("Please use remote Swift packages (`swiftPackage`) instead. With dependency on local Swift package the module could still be published to the local " +
                "Maven repository (`mavenLocal`).")
    }
}
