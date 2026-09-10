/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.directSwiftPMDependencies
import org.jetbrains.amper.swiftpm.SwiftPMImportMetadata
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.IOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.MACOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.TVOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.WATCHOS_DEPLOYMENT_TARGET_DEFAULT

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
 * The SwiftPM metadata that this module must publish so that its consumers know which SwiftPM packages they have to
 * fetch and link, or null if this module declares no SwiftPM dependency (in which case nothing is published, exactly
 * like in Gradle builds).
 *
 * Only the SwiftPM dependencies declared by this module are published: the ones of its own dependencies are published
 * by those dependencies, and consumers gather them by walking their whole dependency graph (this is what
 * [TransitiveSwiftPMDependenciesResolver] does on our side).
 */
internal fun AmperModule.swiftPMImportMetadataForPublication(): SwiftPMImportMetadata? {
    val dependencies = directSwiftPMDependencies().map { it.swiftPMDependency }.toSet()
    if (dependencies.isEmpty()) return null

    return SwiftPMImportMetadata(
        // Consumers use these to know which of their own targets this metadata applies to. They are KonanTarget names,
        // like in the KGP publication, and not Kotlin Toolchain platform names.
        konanTargets = leafAppleFragments().map { it.platform.konanTargetName() }.sorted().toSet(),
        /**
         * In the Gradle implementation these versions are specified in the DSL, published by the project and
         * are eventually consumed. Values are hardcoded for now, see the same FIXME in [TransitiveSwiftPMDependenciesResolver].
         */
        iosDeploymentVersion = IOS_DEPLOYMENT_TARGET_DEFAULT,
        macosDeploymentVersion = MACOS_DEPLOYMENT_TARGET_DEFAULT,
        watchosDeploymentVersion = WATCHOS_DEPLOYMENT_TARGET_DEFAULT,
        tvosDeploymentVersion = TVOS_DEPLOYMENT_TARGET_DEFAULT,
        isModulesDiscoveryEnabled = true,
        dependencies = dependencies,
    )
}
