/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.frontend.schema.swiftpm.SwiftPMDependency

interface SwiftPMDependencyNodeFromAmperModule: DependencyNode {
    val swiftPMDependency: SwiftPMDependency

    val amperModuleName: String
    val platformQualifier: String?

//     FIXME: Discuss, maybe it's better to use this index instead of serializing this dependency whenever we need the key
//     val swiftPMDependencyDeclarationIndex: Int
//    override val graphEntryName: String
//        get() = "SwiftPMDependency:${amperModuleName}:${platformQualifier}:${swiftPMDependencyDeclarationIndex}"

    override val graphEntryName: String
        get() = "SwiftPMDependency:${amperModuleName}:${json.encodeToString(swiftPMDependency)}"
}

private val json = Json {
    encodeDefaults = true
    explicitNulls = false
}