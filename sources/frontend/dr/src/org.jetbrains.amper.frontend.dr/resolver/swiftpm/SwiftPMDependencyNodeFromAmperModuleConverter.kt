/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeConverter

internal sealed class SwiftPMDependencyNodeFromAmperModuleConverter<T: SwiftPMDependencyNodeFromAmperModule>:
    SerializableDependencyNodeConverter<T, SerializableSwiftPMDependencyNodeFromAmperModule> {
    object Input: SwiftPMDependencyNodeFromAmperModuleConverter<SwiftPMDependencyNodeFromAmperModuleImpl>() {
        override fun applicableTo() = SwiftPMDependencyNodeFromAmperModuleImpl::class
    }
    object Plain: SwiftPMDependencyNodeFromAmperModuleConverter<SerializableSwiftPMDependencyNodeFromAmperModule>() {
        override fun applicableTo() = SerializableSwiftPMDependencyNodeFromAmperModule::class
    }

    override fun toEmptyNodePlain(node: T, graphContext: DependencyGraphContext): SerializableSwiftPMDependencyNodeFromAmperModule =
        SerializableSwiftPMDependencyNodeFromAmperModule(
            swiftPMDependency = node.swiftPMDependency,
//            swiftPMDependencyDeclarationIndex = node.swiftPMDependencyDeclarationIndex,
            amperModuleName = node.amperModuleName,
            platformQualifier = node.platformQualifier,
            graphContext = graphContext,
        )

    companion object {
        fun converters()= listOf(Input, Plain)
    }
}