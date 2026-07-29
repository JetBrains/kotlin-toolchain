/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.amper.dependency.resolution.CacheEntryKey
import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolder
import org.jetbrains.amper.dependency.resolution.DependencyNodeReference
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeHolderBase
import org.jetbrains.amper.dependency.resolution.currentGraphContext
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.SwiftPMDependencyNotation
import org.jetbrains.amper.swiftpm.SwiftPMDependency

@Serializable
@SerialName("SPMDDN")
class SerializableSwiftPMDependencyNodeFromAmperModule(
    override val swiftPMDependency: SwiftPMDependency,
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext(),
) : SwiftPMDependencyNodeFromAmperModule, SerializableDependencyNodeHolderBase(graphContext) {

    @Transient
    override lateinit var notation: SwiftPMDependencyNotation

    override val childrenRefs: List<DependencyNodeReference> by lazy { emptyList() }
    override val messages: List<Message> by lazy { emptyList() }
    override val key by lazy { Key<DependencyNodeHolder>(identifier()) }
}

