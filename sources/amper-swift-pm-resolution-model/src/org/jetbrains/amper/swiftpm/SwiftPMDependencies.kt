/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.swiftpm

import kotlinx.serialization.Serializable

@Serializable
data class SwiftPMDependencies(
    val directSwiftPMDependencies: Set<SwiftPMDependency>,
    val transitiveSwiftPMDependencies: TransitiveSwiftPMMetadata,
) {
    val hasDirectOrTransitiveSwiftPMDependencies
        get() = directSwiftPMDependencies.isNotEmpty() || transitiveSwiftPMDependencies.metadataByDependencyIdentifier.isNotEmpty()

    val localPackageDependencies
        get() = (directSwiftPMDependencies.filterIsInstance<SwiftPMDependency.Local>() + transitiveSwiftPMDependencies.metadataByDependencyIdentifier.values.flatMap {
            it.dependencies.filterIsInstance<SwiftPMDependency.Local>()
        }).associateBy { it.absolutePath }.values
}