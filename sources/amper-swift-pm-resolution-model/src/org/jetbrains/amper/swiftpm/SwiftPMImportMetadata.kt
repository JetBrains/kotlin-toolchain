/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.swiftpm

import kotlinx.serialization.Serializable

@Serializable
data class SwiftPMImportMetadata(
    val konanTargets: Set<String>,
    val iosDeploymentVersion: String?,
    val macosDeploymentVersion: String?,
    val watchosDeploymentVersion: String?,
    val tvosDeploymentVersion: String?,
    @Suppress("unused")
    val isModulesDiscoveryEnabled: Boolean,
    val dependencies: Set<SwiftPMDependency>,
)