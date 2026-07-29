/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.frontend.SwiftPMDependencyNotation
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.swiftpm.SwiftPMDependency
import org.jetbrains.amper.swiftpm.swiftPMJson

interface SwiftPMDependencyNodeFromAmperModule: DependencyNode {
    val swiftPMDependency: SwiftPMDependency

    /**
     * This relies on these nodes being created per-module
     * in [org.jetbrains.amper.frontend.dr.resolver.swiftpm.SwiftPMDependencyNodeFromAmperModuleImplKt.getOrCreateSwiftPMDependencyNodeFromAmperModule]
     */
    val parentModuleNodes: ModuleDependencyNode
        get() = parents.firstNotNullOf { it as? ModuleDependencyNode }
    val amperModuleName: String
        get() = parentModuleNodes.moduleName

    val notation: SwiftPMDependencyNotation

    override val graphEntryName: String
        get() = identifier()
}

fun SwiftPMDependencyNodeFromAmperModule.identifier() = identifier(amperModuleName, swiftPMDependency)
fun identifier(amperModuleName: String, swiftPMDependency: SwiftPMDependency) = "${amperModuleName}:${swiftPMJson.encodeToString(swiftPMDependency)}"
