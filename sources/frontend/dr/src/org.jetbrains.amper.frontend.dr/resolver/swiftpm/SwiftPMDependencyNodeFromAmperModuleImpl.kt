/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.Cache
import org.jetbrains.amper.dependency.resolution.CacheEntryKey
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolder
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.dr.resolver.DependencyNodeHolderWithNotationAndContext
import org.jetbrains.amper.frontend.dr.resolver.uniqueModuleKey
import org.jetbrains.amper.frontend.schema.swiftpm.SwiftPMDependency

class SwiftPMDependencyNodeFromAmperModuleImpl(
    val module: AmperModule,
    override val swiftPMDependency: SwiftPMDependency,
    // override val swiftPMDependencyDeclarationIndex: Int,
    override val platformQualifier: String?,
    override val notation: Notation,
    templateContext: Context,
) : SwiftPMDependencyNodeFromAmperModule, DependencyNodeHolderWithNotationAndContext(
    children = emptyList(),
    parentNodes = emptySet(),
    templateContext = templateContext,
    notation = notation,
) {
    override val amperModuleName: String = module.userReadableName

    override val key by lazy { Key<DependencyNodeHolder>(cacheEntryKey.computeKey()) }
    override val cacheEntryKey: CacheEntryKey.CompositeCacheEntryKey by lazy {
        CacheEntryKey.CompositeCacheEntryKey(listOf(
            module.uniqueModuleKey(),
            graphEntryName,
        ))
    }
}

fun getOrCreateSwiftPMDependencyNodeFromAmperModule(
    // Use per module's cache specifically. These nodes don't depend on a context and should be unique per module
    sharedResolutionCache: Cache,
    module: AmperModule,
    swiftPMDependency: SwiftPMDependency,
    // swiftPMDependencyDeclarationIndex: Int,
    platformQualifier: String?,
    notation: Notation,
    templateContext: Context,
): SwiftPMDependencyNodeFromAmperModuleImpl = sharedResolutionCache.computeIfAbsent(
    Key<SwiftPMDependencyNodeFromAmperModuleImpl>(
        json.encodeToString(swiftPMDependency)
//        "${module.userReadableName}:${platformQualifier}:${swiftPMDependencyDeclarationIndex}"
    )
) {
    SwiftPMDependencyNodeFromAmperModuleImpl(
        module = module,
        swiftPMDependency = swiftPMDependency,
        // swiftPMDependencyDeclarationIndex = swiftPMDependencyDeclarationIndex,
        platformQualifier = platformQualifier,
        notation = notation,
        templateContext = templateContext,
    )
}

private val json = Json {
    encodeDefaults = true
    explicitNulls = false
}