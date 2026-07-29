/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.frontend.dr.resolver

import kotlinx.serialization.modules.SerializersModuleBuilder
import org.jetbrains.amper.dependency.resolution.DependencyGraph.Companion.toSerializableReference
import org.jetbrains.amper.dependency.resolution.DependencyGraphContext
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.DependencyNodeReference
import org.jetbrains.amper.dependency.resolution.GraphSerializableTypesProvider
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.MavenDependencyUnspecifiedVersionResolverBase
import org.jetbrains.amper.dependency.resolution.ResolutionConfigPlain
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNode
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNodeConverter
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.SwiftPMDependenciesMetadataNodeConverter
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.SerializableSwiftPMDependenciesMetadataNode
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.SerializableSwiftPMDependencyNodeFromAmperModule
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.SwiftPMDependencyNodeFromAmperModuleConverter
import kotlin.reflect.KClass

// todo (AB) : [AMPER-4905] Extract to separate serialization-specific file
internal class AmperDrSerializableTypesProvider: GraphSerializableTypesProvider {
    override fun getSerializableConverters() =
        ModuleDependencyNodeWithModuleConverter.converters() +
                DirectFragmentDependencyNodeConverter.converters() +
                SwiftPMDependencyNodeFromAmperModuleConverter.converters() +
                SwiftPMDependenciesMetadataNodeConverter.converters()

    override fun SerializersModuleBuilder.registerPolymorphic() {
        moduleForDependencyNodePlainHierarchy()
        moduleForDependencyNodeHierarchy()
    }

    fun SerializersModuleBuilder.moduleForDependencyNodePlainHierarchy() =
        moduleForDependencyNodeHierarchy(SerializableDependencyNode::class)

    fun SerializersModuleBuilder.moduleForDependencyNodeHierarchy() =
        moduleForDependencyNodeHierarchy(DependencyNode::class)

    fun SerializersModuleBuilder.moduleForDependencyNodeHierarchy(kClass: KClass<in SerializableDependencyNode>) {
        polymorphic(kClass, SerializableModuleDependencyNodeWithModule::class, SerializableModuleDependencyNodeWithModule.serializer())
        polymorphic(kClass, SerializableDirectFragmentDependencyNodeHolder::class, SerializableDirectFragmentDependencyNodeHolder.serializer())
        polymorphic(kClass, SerializableSwiftPMDependencyNodeFromAmperModule::class, SerializableSwiftPMDependencyNodeFromAmperModule.serializer())
        polymorphic(kClass, SerializableSwiftPMDependenciesMetadataNode::class, SerializableSwiftPMDependenciesMetadataNode.serializer())
    }
}

// todo (AB) : [AMPER-4905] Extract to separate serialization-specific file
private sealed class ModuleDependencyNodeWithModuleConverter<T: ModuleDependencyNode>: SerializableDependencyNodeConverter<T, SerializableModuleDependencyNodeWithModule>  {
    object Input: ModuleDependencyNodeWithModuleConverter<ModuleDependencyNodeWithModuleAndContext>() {
        override fun applicableTo() = ModuleDependencyNodeWithModuleAndContext::class
    }
    object Plain: ModuleDependencyNodeWithModuleConverter<SerializableModuleDependencyNodeWithModule>() {
        override fun applicableTo() = SerializableModuleDependencyNodeWithModule::class
    }

    override fun toEmptyNodePlain(node: T, graphContext: DependencyGraphContext): SerializableModuleDependencyNodeWithModule =
        SerializableModuleDependencyNodeWithModule(
            node.moduleName,
            node.isForTests,
            ResolutionConfigPlain(node.resolutionConfig),
            topLevel = node.topLevel,
            graphContext = graphContext,
        )

    companion object {
        fun converters()= listOf(Input, Plain)
    }
}

// todo (AB) : [AMPER-4905] Extract to separate serialization-specific file
private sealed class DirectFragmentDependencyNodeConverter<T: DirectFragmentDependencyNode>
    : SerializableDependencyNodeConverter<T, SerializableDirectFragmentDependencyNodeHolder>
{
    object Input: DirectFragmentDependencyNodeConverter<DirectFragmentDependencyNodeHolderWithContext>() {
        override fun applicableTo() = DirectFragmentDependencyNodeHolderWithContext::class
    }
    object Plain: DirectFragmentDependencyNodeConverter<SerializableDirectFragmentDependencyNodeHolder>() {
        override fun applicableTo() = SerializableDirectFragmentDependencyNodeHolder::class
    }

    override fun toEmptyNodePlain(node: T, graphContext: DependencyGraphContext): SerializableDirectFragmentDependencyNodeHolder =
        SerializableDirectFragmentDependencyNodeHolder(
            node.fragmentName, node.moduleName, node.traceInfo, node.messages, node.isTransitive, graphContext = graphContext)

    override fun fillEmptyNodePlain(nodePlain: SerializableDirectFragmentDependencyNodeHolder, node: T, graphContext: DependencyGraphContext, nodeReference: DependencyNodeReference?) {
        super.fillEmptyNodePlain(nodePlain, node, graphContext, nodeReference)
        nodePlain.dependencyNodeRef =
            graphContext.getDependencyNodeReferenceAndSetParent(node.dependencyNode, nodeReference)
                ?: node.dependencyNode.toSerializableReference(graphContext,nodeReference)
    }

    companion object {
        fun converters() = listOf(Input, Plain)
    }
}

// todo (AB) : [AMPER-4905] Move to ModuleDependencies
class DirectMavenDependencyUnspecifiedVersionResolver: MavenDependencyUnspecifiedVersionResolverBase() {

    override fun getBomNodes(node: MavenDependencyNodeWithContext): List<MavenDependencyNode> {
        val directDependencyParents = node.directDependencyParents()
        val boms = if (directDependencyParents.isNotEmpty()) {
            // Using BOM from the same module for resolving direct module dependencies
            directDependencyParents
                .mapNotNull { it.parents.singleOrNull() as? ModuleDependencyNode }
                .map { parent ->
                    parent.children
                        .filterIsInstance<DirectFragmentDependencyNode>()
                        .map { it.dependencyNode }
                        .filterBomDependencies()
                }.flatten()
        } else {
            super.getBomNodes(node)
        }

        return boms
    }

    /**
     * @return list of [DirectFragmentDependencyNode]s that depend on this maven libary (either directly or transitevly)
     */
    private fun MavenDependencyNodeWithContext.directDependencyParents(): List<DirectFragmentDependencyNode> {
        return when {
            // Direct dependency
            parents.any { it is DirectFragmentDependencyNode } -> parents.filterIsInstance<DirectFragmentDependencyNode>()
            // Transitive dependency,
            // find all direct dependencies this transitive one is referenced by and use those for BOM resolution
            else -> fragmentDependencies
        }
    }
}
