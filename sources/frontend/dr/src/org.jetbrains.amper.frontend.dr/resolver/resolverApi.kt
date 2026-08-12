/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.frontend.dr.resolver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.amper.dependency.resolution.CacheEntryKey
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolder
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolderWithContext
import org.jetbrains.amper.dependency.resolution.DependencyNodeReference
import org.jetbrains.amper.dependency.resolution.DependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.Key
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.ResolutionConfig
import org.jetbrains.amper.dependency.resolution.ResolutionConfigPlain
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.RootDependencyNode
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeHolderBase
import org.jetbrains.amper.dependency.resolution.currentGraphContext
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.nodeParents
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.api.BuiltinCatalogTrace
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.PsiTrace
import org.jetbrains.amper.frontend.api.ResolvedReferenceTrace
import org.jetbrains.amper.frontend.api.TransformedValueTrace
import org.jetbrains.amper.frontend.dr.resolver.flow.toPlatform

enum class ResolutionDepth {
    GRAPH_ONLY,
    GRAPH_WITH_DIRECT_DEPENDENCIES,
    GRAPH_FULL
}

sealed interface DependenciesFlowType {
    data class ClassPathType(
        val scope: ResolutionScope,
        val platforms: Set<ResolutionPlatform>,
        val isTest: Boolean,
        val includeNonExportedNative: Boolean = true,
    ) : DependenciesFlowType
}

/**
 * Filter resolution results.
 * Resolution is executed module wide, aligning versions for all platforms' dependencies and across RUNTIME/COMPILE scopes.
 * To avoid tests classpath affecting the main classpath main and test resolution are still isolated.
 *
 * This filter is intended to be used If the caller needs resolution results for specific platforms/scope only.
 */
data class ModuleResolutionFilter(
    val scope: ResolutionScope? = null,
    val platforms: Set<ResolutionPlatform>? = null,
    val resolutionType: ResolutionType = ResolutionType.MAIN,
)

internal val defaultModuleResolutionFilter = ModuleResolutionFilter()

abstract class DependencyNodeHolderWithNotationAndContext(
    children: List<DependencyNodeWithContext>,
    templateContext: Context,
    open val notation: Notation? = null,
    parentNodes: Set<DependencyNodeWithContext> = emptySet(),
) : DependencyNodeHolderWithContext(children, templateContext, parentNodes)

interface ModuleDependencyNode: DependencyNodeHolder {
    val moduleName: String
    val notation: LocalModuleDependency?
    val isForTests: Boolean
    val resolutionConfig: ResolutionConfig
    val topLevel: Boolean

    override val graphEntryName: String get() {
        val graphEntryNameBuilder = StringBuilder("Module $moduleName")
        if (topLevel) {
            graphEntryNameBuilder
                .append("\n")
                .append(
                    """│ - ${if (isForTests) "test" else "main"}
                  |│ - scope = ${resolutionConfig.scope.name}
                  |│ - platforms = [${resolutionConfig.platforms.joinToString { it.toPlatform().pretty }}]
                  """.trimMargin()
                )
        }
        return graphEntryNameBuilder.toString()
    }

    fun attachToNewRoot(parent: RootDependencyNode)
}

fun ModuleDependencyNode.getKey(): Key<DependencyNodeHolder> = Key<DependencyNodeHolder>(
    CacheEntryKey.CompositeCacheEntryKey(
        listOf(
        moduleName,
        isForTests,
        resolutionConfig.scope,
        resolutionConfig.platforms
    )).computeKey()
)

class ModuleDependencyNodeWithModuleAndContext internal constructor(
    val module: AmperModule,
    override val isForTests: Boolean,
    children: List<DependencyNodeWithContext>,
    templateContext: Context,
    override val notation: LocalModuleDependency? = null,
    parentNodes: Set<DependencyNodeWithContext> = emptySet(),
    override val topLevel: Boolean,
) : ModuleDependencyNode,
    DependencyNodeHolderWithNotationAndContext(children, templateContext, notation, parentNodes = parentNodes)
{
    override val moduleName = module.userReadableName

    override val resolutionConfig: ResolutionConfig
        get() = context.settings

    override val cacheEntryKey: CacheEntryKey.CompositeCacheEntryKey
        get() = CacheEntryKey.CompositeCacheEntryKey(listOf(
            module.uniqueModuleKey(),
            isForTests
        ))

    override fun attachToNewRoot(parent: RootDependencyNode) {
        context.nodeParents.clear()
        context.nodeParents.add(parent)
    }

    override val key = getKey()
}

@Serializable
@SerialName("ModuleDN")
internal class SerializableModuleDependencyNodeWithModule internal constructor(
    override val moduleName: String,
    override val isForTests: Boolean = false,
    override val resolutionConfig: ResolutionConfigPlain,
    override val topLevel: Boolean = false,
    override val childrenRefs: List<DependencyNodeReference> = mutableListOf(),
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext(),
): ModuleDependencyNode, SerializableDependencyNodeHolderBase(graphContext) {

    override val messages: List<Message> = emptyList()

    @Transient
    override var notation: LocalModuleDependency? = null

    override fun attachToNewRoot(parent: RootDependencyNode) {
        parents.clear()
        parents.add(parent)
    }

    override val key by lazy { getKey() }

}

interface DirectFragmentDependencyNode: DependencyNodeHolder {
    val fragmentName: String
    val moduleName: String
    val dependencyNode: MavenDependencyNode
    val traceInfo: String
    val notation: MavenDependencyBase
    val isTransitive: Boolean

    /**
     * Original not-resolved state of a direct dependency declaration
     */
    override val graphEntryName: String get() =
        "$moduleName:$fragmentName:${dependencyNode.getOriginalMavenCoordinates().toPrettyString()}$traceInfo"
}

fun DirectFragmentDependencyNode.getKey() =
    Key<DependencyNodeHolder>(
        CacheEntryKey.CompositeCacheEntryKey(listOf(
                moduleName,
                fragmentName,
                isTransitive,
                dependencyNode.getOriginalMavenCoordinates(),
                traceInfo,
                dependencyNode.dependency.resolutionConfig.scope, // scope only, platforms are distinguished by fragment name already
            )).computeKey()
    )

internal class DirectFragmentDependencyNodeHolderWithContext(
    override val dependencyNode: MavenDependencyNodeWithContext,
    val fragment: Fragment,
    templateContext: Context,
    override val isTransitive: Boolean = false,
    override val notation: MavenDependencyBase,
    parentNodes: Set<DependencyNodeWithContext> = emptySet(),
    override val messages: List<Message> = emptyList(),
) : DirectFragmentDependencyNode,
    DependencyNodeHolderWithNotationAndContext(listOf(dependencyNode), templateContext, notation, parentNodes)
{
    override val fragmentName: String = fragment.name
    override val moduleName: String = fragment.module.userReadableName
    override val traceInfo: String = traceInfo(notation)

    override val cacheEntryKey: CacheEntryKey.CompositeCacheEntryKey
        get() = CacheEntryKey.CompositeCacheEntryKey(listOf(
            fragment.module.uniqueModuleKey(),
            fragment.name,
            dependencyNode.cacheEntryKey,
        ))

    override val key by lazy { getKey() }
}

private fun traceInfo(notation: Notation): String {
    val sourceInfo = when (val trace = notation.trace) {
        is DefaultTrace -> "implicit"
        is TransformedValueTrace -> "implicit (${trace.description})"
        is ResolvedReferenceTrace -> trace.description
        is BuiltinCatalogTrace -> null // should never happen for dependency Notation
        // TODO maybe write something if the dependency comes from a template?
        is PsiTrace -> null // we don't want to clutter the output for 'regular' dependencies declared in files
    }
    return sourceInfo?.let { ", $it" } ?: ""
}

@Serializable
@SerialName("DirectDN")
internal class SerializableDirectFragmentDependencyNodeHolder internal constructor(
    override val fragmentName: String,
    override val moduleName: String,
    override val traceInfo: String = "",
    override val messages: List<Message> = emptyList(),
    override val isTransitive: Boolean = false,
    override val childrenRefs: List<DependencyNodeReference> = mutableListOf(),
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext()
): DirectFragmentDependencyNode, SerializableDependencyNodeHolderBase(graphContext) {

    lateinit var dependencyNodeRef: DependencyNodeReference

    override val dependencyNode: MavenDependencyNode by lazy {
        dependencyNodeRef.toNodePlain(graphContext)
            .let {
                it as? MavenDependencyNode
                    ?: error("Unexpected dependency node type [${it::class.simpleName}]")
            }
    }

    override val key by lazy { getKey() }

    @Transient
    override lateinit var notation: MavenDependencyBase
}