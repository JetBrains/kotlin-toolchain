/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.swiftpm

import org.jetbrains.amper.dependency.resolution.CacheEntryKey
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyFileImpl
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.DependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.MavenDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.ResolutionLevel
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.nodeParents
import java.nio.file.Path

class SwiftPMDependenciesMetadataNodeImpl(
    private val dependencyFile: DependencyFileImpl,
    val parentMavenNode: MavenDependencyNodeWithContext,
    templateContext: Context,
) : DependencyNodeWithContext, SwiftPMDependenciesMetadataNode {
    override val swiftPMMetadataPath: Path = dependencyFile.path ?: error("Dependency file is missing metadata path: $dependencyFile")

    override val context: Context = templateContext.copyWithNewNodeCache(setOf(parentMavenNode))

    override val parents: Set<DependencyNode> get() = context.nodeParents
    override val children: List<DependencyNodeWithContext>
        get() = emptyList()
    override val messages: List<Message>
        get() = emptyList()

    override suspend fun downloadDependencies(downloadSources: Boolean) {
        if (!dependencyFile.isDownloadedWithVerification(settings = context.settings)) {
            dependencyFile.download(context = context, diagnosticsReporter = dependencyFile.diagnosticsReporter)
        }
    }
    override suspend fun resolveChildren(level: ResolutionLevel, transitive: Boolean) {}

    override val cacheEntryKey: CacheEntryKey by lazy {
        CacheEntryKey.CompositeCacheEntryKey(
            listOf("swiftPMDependenciesMetadataNode") + (parentMavenNode.cacheEntryKey as CacheEntryKey.CompositeCacheEntryKey).components
        )
    }
    override val key: Key<SwiftPMDependenciesMetadataNode> by lazy {
        Key<SwiftPMDependenciesMetadataNode>(
            "swiftPMDependenciesMetadataNode:${parentMavenNode.key.name}"
        )
    }
}