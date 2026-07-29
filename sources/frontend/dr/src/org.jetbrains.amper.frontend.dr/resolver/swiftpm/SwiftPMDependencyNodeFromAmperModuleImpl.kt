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
import org.jetbrains.amper.frontend.SwiftPMDependencyNotation
import org.jetbrains.amper.frontend.dr.resolver.DependencyNodeHolderWithNotationAndContext
import org.jetbrains.amper.swiftpm.SwiftPMDependency

class SwiftPMDependencyNodeFromAmperModuleImpl(
    override val swiftPMDependency: SwiftPMDependency,
    override val notation: SwiftPMDependencyNotation,
    templateContext: Context,
) : SwiftPMDependencyNodeFromAmperModule, DependencyNodeHolderWithNotationAndContext(
    children = emptyList(),
    parentNodes = emptySet(),
    templateContext = templateContext,
    notation = notation,
) {
    private val identifier by lazy { identifier() }
    override val key by lazy { Key<DependencyNodeHolder>(identifier) }
    override val cacheEntryKey: CacheEntryKey.CompositeCacheEntryKey by lazy {
        CacheEntryKey.CompositeCacheEntryKey(listOf(identifier))
    }
}