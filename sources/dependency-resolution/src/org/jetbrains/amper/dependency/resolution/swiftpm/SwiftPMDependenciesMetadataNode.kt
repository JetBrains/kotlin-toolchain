/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.swiftpm

import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import java.nio.file.Path

interface SwiftPMDependenciesMetadataNode : DependencyNode {
    val swiftPMMetadataPath: Path

    override val graphEntryName: String
        get() = "swiftPMDependenciesMetadataNode:${parents.single { it is MavenDependencyNode }.graphEntryName}"
}