/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.directSwiftPMDependencies
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
