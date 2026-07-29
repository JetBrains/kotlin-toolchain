/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.DependencyNodeReference
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeHolderBase
import org.jetbrains.amper.dependency.resolution.currentGraphContext
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.swiftpm.SwiftPMDependenciesMetadataNode
import org.jetbrains.amper.serialization.paths.SerializablePath

@Serializable
@SerialName("SwiftPMDependenciesMetadataFromMavenDN")
class SerializableSwiftPMDependenciesMetadataNode(
    override val swiftPMMetadataPath: SerializablePath,
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext(),
) : SerializableDependencyNodeHolderBase(graphContext), SwiftPMDependenciesMetadataNode {
    override val messages: List<Message> = emptyList()
    override val childrenRefs: List<DependencyNodeReference> = emptyList()

    override val key: Key<SwiftPMDependenciesMetadataNode> by lazy {
        Key<SwiftPMDependenciesMetadataNode>(
            "swiftPMDependenciesMetadataNode:${parents.single { it is MavenDependencyNode }.key.name}"
        )
    }
}

