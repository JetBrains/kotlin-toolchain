/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.swiftpm

import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeConverter

internal object SwiftPMDependenciesMetadataNodeConverter {
    object Input:
        SerializableDependencyNodeConverter<SwiftPMDependenciesMetadataNodeImpl, SerializableSwiftPMDependenciesMetadataNode> {
        override fun applicableTo() = SwiftPMDependenciesMetadataNodeImpl::class
        override fun toEmptyNodePlain(
            node: SwiftPMDependenciesMetadataNodeImpl,
            graphContext: DependencyGraphContext,
        ): SerializableSwiftPMDependenciesMetadataNode = SerializableSwiftPMDependenciesMetadataNode(
            swiftPMMetadataPath = node.swiftPMMetadataPath,
            graphContext = graphContext,
        )
    }
    object Plain:
        SerializableDependencyNodeConverter<SerializableSwiftPMDependenciesMetadataNode, SerializableSwiftPMDependenciesMetadataNode> {
        override fun applicableTo() = SerializableSwiftPMDependenciesMetadataNode::class
        override fun toEmptyNodePlain(
            node: SerializableSwiftPMDependenciesMetadataNode,
            graphContext: DependencyGraphContext,
        ): SerializableSwiftPMDependenciesMetadataNode = SerializableSwiftPMDependenciesMetadataNode(
            swiftPMMetadataPath = node.swiftPMMetadataPath,
            graphContext = graphContext,
        )
    }

    fun converters()= listOf(
        Input,
        Plain
    )
}