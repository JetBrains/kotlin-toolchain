/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.DependencyNodeReference
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeHolderBase
import org.jetbrains.amper.dependency.resolution.currentGraphContext
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.frontend.schema.swiftpm.SwiftPMDependency

@Serializable
@SerialName("SwiftPMDependencyNodeFromAmperModule")
class SerializableSwiftPMDependencyNodeFromAmperModule(
    override val swiftPMDependency: SwiftPMDependency,
//    override val swiftPMDependencyDeclarationIndex: Int,
    override val amperModuleName: String,
    override val platformQualifier: String?,
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext(),
) : SwiftPMDependencyNodeFromAmperModule, SerializableDependencyNodeHolderBase(graphContext) {
    override val childrenRefs: List<DependencyNodeReference> = emptyList()
    override val messages: List<Message> = emptyList()
}

