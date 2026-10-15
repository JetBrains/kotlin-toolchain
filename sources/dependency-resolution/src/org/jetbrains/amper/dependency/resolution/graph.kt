/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import java.util.*
import kotlin.reflect.KClass

/**
 * A dependency node is a graph element.
 *
 * It has the following properties.
 *
 * - Could have children, the loops are prohibited.
 * - Can be compared by a [Key] that's the same for all nodes with equal coordinates but different versions.
 *
 * By the resolution process we mean finding the node's dependencies (children) according to provided context,
 * namely, a [ResolutionScope] and a platform.
 */
interface DependencyNode {
    val parents: Set<DependencyNode>
    val key: Key<*>
    val children: List<DependencyNode>
    val messages: List<Message>

    /**
     * String representation of the node used in the dependency graph output produced by the command 'show dependencies'
     */
    val graphEntryName: String
}

fun DependencyNode.unwrap(): DependencyNode =
    when (this) {
        is DependencyNodeWithChildren -> node.unwrap()
        else -> this
    }

@Serializable
class DependencyGraph(
    val graphContext: DependencyGraphContext,
    private val rootReference: DependencyNodeReference,
) {
    val root: SerializableDependencyNode by lazy { rootReference.toNodePlain(graphContext)}

    companion object {
        fun DependencyNode.toGraph(): DependencyGraph {
            val graphContext = DependencyGraphContext()
            val nodeReference = toSerializableReference(graphContext, null)
            return DependencyGraph(graphContext, nodeReference)
        }

        internal val providers by lazy {
            listOf(DefaultSerializableTypesProvider()) +
                    ServiceLoader.load(
                        GraphSerializableTypesProvider::class.java,
                        // By default, if the parameter loader is left unspecified,
                        // Thread Context ClassLoader (CCTL) is used for loading services.
                        // When this code is called inside Amper Idea Plugin,
                        // CCTL is assigned to the instance com.intellij.util.lang.UrlClassLoader
                        // that is unaware of the plugin's classpath (see https://youtrack.jetbrains.com/issue/IJPL-116331).
                        // Thus, if we don't specify a classloader,
                        // implementations of [GraphSerializableTypesProvider] from the Amper plugin classpath won't be found.
                        // To make it work, we pass the classloader used for loading 'dependency-resolution' library itself.
                        // Although this works for both primary use cases of the DR library
                        // (which are usages in Amper CLI and Amper Idea plugin),
                        // it implies the following limitation in general:
                        // if a consumer extends DR with new types of graph nodes and declares implementation of
                        // [GraphSerializableTypesProvider], such an implementation should be loadable by the
                        // classloader that was used for loading 'dependency-resolution' library on consumer side.
                        GraphSerializableTypesProvider::class.java.classLoader
                    )
        }

        private val converters: Map<KClass<out DependencyNode>, SerializableDependencyNodeConverter<*, *>>
                by lazy {
                    buildMap {
                        providers
                            .flatMap { it.getSerializableConverters() }
                            .register(this)
                    }
                }

        private fun <T : DependencyNode> getConverter(node: T): SerializableDependencyNodeConverter<T, *> =
            converters[node::class]
                ?.let {
                    @Suppress("UNCHECKED_CAST") // safe by definition of applicableTo and by construction of the map
                    it as SerializableDependencyNodeConverter<T, *>
                }
                ?: error("Converter for the node ${node::class} is not registered")

        private fun List<SerializableDependencyNodeConverter<*, *>>.register(
            map: MutableMap<KClass<out DependencyNode>, SerializableDependencyNodeConverter<*, *>>
        )= forEach {
            if (map.contains(it.applicableTo()))
                error("Converter for the node of type ${it.applicableTo()} is already registered: (${map[it.applicableTo()]!!::class}), " +
                        "can't register one more converter of type  ${it::class} for the same node type")
            map[it.applicableTo()] = it
        }

        /**
         * This method creates a serializable representation of the node
         * (which is presented by the type implementing [SerializableDependencyNode] and annotated with [Serializable]),
         * and register it in the given context, getting [DependencyNodeReference] as a result of the registration.
         *
         * Children of the nodes in the created [SerializableDependencyNode] are represented with the references [DependencyNodeReference]
         * and obtained by traversing the children of this node calling this method recursively.
         *
         * To prevent loops during serialization, the creation of the serializable object representing the node
         * is split into two steps:
         * 1. First, a plain data object is created with the help of [SerializableDependencyNodeConverter.toEmptyNodePlain]
         * with all references on other nodes left empty, but all other plain data get filled.
         * This plain object is immediately registered in the given context,
         * getting [DependencyNodeReference] as a result of the registration.
         * 2. Before the method returns the resulting reference,
         * the second step is performed that populates references to other nodes: [SerializableDependencyNodeConverter.fillEmptyNodePlain].
         * First, children of the node are populated calling this method recursively.
         * Since the resolution graph doesn't contain loops, after recursion is finished,
         * all nodes of the graph will be registered in the given context.
         * After that, all other kinds of references to the nodes might be populated (in an overridden [SerializableDependencyNodeConverter.fillEmptyNodePlain])
         *
         * @context holds reusable objects that could be referenced from the node. Context is mutable.
         * If the node references some reusable object,
         * then this method should add the object into the context and use a Reference type for the returned Serializable form.
         * This way the same object won't be stored several times in JSON and will be correctly deserialized
         * from the given context
         *
         * @parent is a reference to the parent node of the node that is being serialized.
         * it is propagated to the API of [DependencyGraphContext] that registers it as a parent of the node.
         */
        fun DependencyNode.toSerializableReference(graphContext: DependencyGraphContext, parent: DependencyNodeReference?): DependencyNodeReference {
            return graphContext.getDependencyNodeReferenceAndSetParent(this, parent)
                ?: context(graphContext) {
                    getConverter(this).convertAndGetReference(this, parent)
                }
        }

        context(graphContext: DependencyGraphContext)
        private fun <S : SerializableDependencyNode> SerializableDependencyNodeConverter<DependencyNode, S>.convertAndGetReference(
            node: DependencyNode,
            parent: DependencyNodeReference?,
        ): DependencyNodeReference {
            // 1. Create an empty reference first (to break cycles)
            val newNodePlain = toEmptyNodePlain(node, graphContext)

            // 2. register empty reference (to break cycles)
            val newReference = graphContext.registerSerializableDependencyNodeWithParent(node, newNodePlain, parent)

            // 3. enrich it with references
            fillEmptyNodePlain(newNodePlain, node, graphContext, newReference)
            return newReference
        }
    }
}

@Serializable(with = DependencyNodeReference.Serializer::class)
data class DependencyNodeReference(
    override val index: DependencyNodeIndex
): GraphEntryReference {
    fun toNodePlain(graphContext: DependencyGraphContext): SerializableDependencyNode =
        graphContext.getDependencyNode(index)

    internal companion object Serializer : ReferenceSerializer<DependencyNodeReference>() {
        override fun Int.toReference() = DependencyNodeReference(this)
        override fun getReferenceClass() = DependencyNodeReference::class
    }
}

internal interface GraphEntryReference {
    val index: Int
}

internal abstract class ReferenceSerializer<R: GraphEntryReference> : KSerializer<R> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(getReferenceClass().java.simpleName, PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: R) {
        encoder.encodeInt(value.index)
    }

    override fun deserialize(decoder: Decoder): R {
        val index = decoder.decodeInt()
        return index.toReference()
    }

    abstract fun Int.toReference(): R
    abstract fun getReferenceClass(): KClass<R>
}

@Serializable(with = DependencyGraphContextSerializer::class)
class DependencyGraphContext(
    val allDependencyNodes: MutableMap<SerializableDependencyNode, DependencyNodeIndex> = mutableMapOf(),
    val allMavenDependencies: MutableMap<MavenDependencyPlain, MavenDependencyIndex> = mutableMapOf(),
    val allMavenDependencyConstraints: MutableMap<MavenDependencyConstraintPlain, MavenDependencyConstraintIndex> = mutableMapOf(),
    val allResolutionConfigs: MutableMap<ResolutionConfigPlain, ResolutionConfigIndex> = mutableMapOf()
) {

    @Transient
    val allDependencyNodeReferences: MutableMap<DependencyNode, DependencyNodeReference> = mutableMapOf()
    @Transient
    val allMavenDependencyReferences: MutableMap<MavenDependency, MavenDependencyReference> = mutableMapOf()
    @Transient
    val allMavenDependencyConstraintReferences: MutableMap<MavenDependencyConstraint, MavenDependencyConstraintReference> = mutableMapOf()
    @Transient
    val allResolutionConfigReferences: MutableMap<ResolutionConfig, ResolutionConfigReference> = mutableMapOf()

    /**
     * The transient fields below provide instant access to cached values by index.
     * It is assumed that indexes used for serialization match the indexes of serialized Map
     *
     * Every index is kept in sync incrementally by the corresponding `register*` method:
     * an entry is appended at the very position that is handed out as its index.
     * Rebuilding an index from the map keys is a fallback for the case when the map was filled in bulk,
     * bypassing the `register*` methods (namely, on graph deserialization,
     * see [DependencyGraphContextSerializer.deserialize]).
     * Rebuilding on every registration would be quadratic in the number of graph entries,
     * which is prohibitively slow for large projects (AMPER-5235).
     */
    @Transient
    private val allDependencyNodesIndex: MutableList<SerializableDependencyNode> = mutableListOf()
    val allDependencyNodesList: List<SerializableDependencyNode>
        get() = allDependencyNodesIndex.resyncedWith(allDependencyNodes)

    @Transient
    private val mavenDependenciesIndex: MutableList<MavenDependencyPlain> = mutableListOf()
    val mavenDependenciesList: List<MavenDependencyPlain>
        get() = mavenDependenciesIndex.resyncedWith(allMavenDependencies)

    @Transient
    private val mavenDependenciesConstraintsIndex: MutableList<MavenDependencyConstraintPlain> = mutableListOf()
    val mavenDependenciesConstraintsList: List<MavenDependencyConstraint>
        get() = mavenDependenciesConstraintsIndex.resyncedWith(allMavenDependencyConstraints)

    @Transient
    private val allResolutionConfigsIndex: MutableList<ResolutionConfigPlain> = mutableListOf()
    val allResolutionConfigsList: List<ResolutionConfigPlain>
        get() = allResolutionConfigsIndex.resyncedWith(allResolutionConfigs)

    /**
     * Rebuilds this index from the keys of [source] if the two have gone out of sync,
     * which only happens when [source] was filled without going through a `register*` method.
     */
    private fun <T> MutableList<T>.resyncedWith(source: Map<T, Int>): MutableList<T> {
        if (size != source.size) {
            clear()
            addAll(source.keys)
        }
        return this
    }

    fun <Node: DependencyNode, NodePlain: SerializableDependencyNode> registerSerializableDependencyNodeWithParent(
        node: Node, nodePlain: NodePlain, parent: DependencyNodeReference?
    ): DependencyNodeReference {
        return registerSerializableDependencyNode(node, nodePlain)
            .also {
                parent?.let { nodePlain.parentsRefs.add(it) }
            }
    }

    private fun <Node: DependencyNode, NodePlain: SerializableDependencyNode> registerSerializableDependencyNode(
        node: Node, nodePlain: NodePlain
    ): DependencyNodeReference {
        if (allDependencyNodeReferences[node] != null) error("Node plain for node $node is already registered")
        if (allDependencyNodes[nodePlain] != null) {
            error("Reference for node $node is already registered")
        }

        val refIndex = allDependencyNodeReferences.size
        allDependencyNodesIndex.resyncedWith(allDependencyNodes).add(nodePlain)
        allDependencyNodes[nodePlain] = refIndex

        val reference = DependencyNodeReference(refIndex)
        allDependencyNodeReferences[node] = reference

        return reference
    }

    fun registerMavenDependencyPlain(mavenDependency: MavenDependency, nodePlain: MavenDependencyPlain): MavenDependencyReference {
        if (allMavenDependencyReferences[mavenDependency] != null) error("Plain maven dependency for maven dependency $mavenDependency is already registered")
        if (allMavenDependencies[nodePlain] != null) error("Reference for node $mavenDependency is already registered")

        val refIndex = allMavenDependencyReferences.size
        mavenDependenciesIndex.resyncedWith(allMavenDependencies).add(nodePlain)
        allMavenDependencies[nodePlain] = refIndex

        val reference = MavenDependencyReference(refIndex)
        allMavenDependencyReferences[mavenDependency] = reference

        return reference
    }

    fun registerMavenDependencyConstraintPlain(constraint: MavenDependencyConstraint, nodePlain: MavenDependencyConstraintPlain): MavenDependencyConstraintReference {
        if (allMavenDependencyConstraintReferences[constraint] != null) error("Plain maven dependency constraint for maven dependency constraint $constraint is already registered")
        if (allMavenDependencyConstraints[nodePlain] != null) error("Reference for node $constraint is already registered")

        val refIndex = allMavenDependencyConstraintReferences.size
        mavenDependenciesConstraintsIndex.resyncedWith(allMavenDependencyConstraints).add(nodePlain)
        allMavenDependencyConstraints[nodePlain] = refIndex

        val reference = MavenDependencyConstraintReference(refIndex)
        allMavenDependencyConstraintReferences[constraint] = reference

        return reference
    }

    fun registerResolutionConfigPlain(resolutionConfig: ResolutionConfig, nodePlain: ResolutionConfigPlain): ResolutionConfigReference {
        if (allResolutionConfigReferences[resolutionConfig] != null) error("Plain resolution config $resolutionConfig is already registered")
        if (allResolutionConfigs[nodePlain] != null) error("Reference for the resolution config $resolutionConfig is already registered")

        val refIndex = allResolutionConfigReferences.size
        allResolutionConfigsIndex.resyncedWith(allResolutionConfigs).add(nodePlain)
        allResolutionConfigs[nodePlain] = refIndex

        val reference = ResolutionConfigReference(refIndex)
        allResolutionConfigReferences[nodePlain] = reference

        return reference
    }

    inline fun <reified T: DependencyNode> getDependencyNodeReference(node: T): DependencyNodeReference? {
        return allDependencyNodeReferences[node]
    }

    inline fun <reified T: DependencyNode> getDependencyNodeReferenceAndSetParent(node: T, parent: DependencyNodeReference?): DependencyNodeReference? {
        return getDependencyNodeReference(node)
            ?.apply {
                val nodePlain = this.toNodePlain(this@DependencyGraphContext)
                parent?.let { nodePlain.parentsRefs.add(it) }
            }
    }

    fun getMavenDependencyReference(mavenDependency: MavenDependency): MavenDependencyReference? {
        return allMavenDependencyReferences[mavenDependency]
    }

    fun getMavenDependencyConstraintReference(constraint: MavenDependencyConstraint): MavenDependencyConstraintReference? {
        return allMavenDependencyConstraintReferences[constraint]
    }

    fun getResolutionConfigReference(resolutionConfig: ResolutionConfig): ResolutionConfigReference? {
        return allResolutionConfigReferences[resolutionConfig]
    }

    inline fun <reified T: SerializableDependencyNode> getDependencyNode(index: DependencyNodeIndex): T {
        val node = allDependencyNodesList.getOrNull(index)

        node ?: run {
            error("Dependency with index $index is absent in the graph")
        }
        (node as? T) ?: error("Dependency with index $index is of type ${node::class.simpleName} while ${T::class.simpleName} is expected")

        return node
    }

    fun getMavenDependency(index: MavenDependencyIndex): MavenDependencyPlain {
        return mavenDependenciesList.getOrNull(index) ?: error("MavenDependency with index $index is absent in the graph")
    }

    fun getMavenDependencyConstraint(index: MavenDependencyConstraintIndex): MavenDependencyConstraint {
        return mavenDependenciesConstraintsList.getOrNull(index) ?: error("MavenDependencyConstraint with index $index is absent in the graph")
    }

    fun getResolutionConfig(index: ResolutionConfigIndex): ResolutionConfigPlain {
        return allResolutionConfigsList.getOrNull(index) ?: error("ResolutionConfig with index $index is absent in the graph")
    }

    /**
     * This is an optimization of the graph footprint.
     * parentRefs are not stored in the graph,
     * because otherwise each edge is stored twice — in both childrenRefs and parentsRefs.
     * Here, we reconstruct parents from children.
     */
    fun rebuildDependencyNodesParentRefs() {
        allDependencyNodesList.forEachIndexed { index, node ->
            val parentRef = DependencyNodeReference(index)
            node.childrenRefs.forEach { childRef ->
                allDependencyNodesList.getOrNull(childRef.index)?.parentsRefs?.add(parentRef)
            }
        }
    }

    companion object {
        val currentGraphContext = ThreadLocal<DependencyGraphContext?>()
    }
}

class DependencyGraphContextSerializer: KSerializer<DependencyGraphContext> {
    private val allDependencyNodesSerializer: KSerializer<Map<SerializableDependencyNode, DependencyNodeIndex>> =
        MapSerializer(PolymorphicSerializer(SerializableDependencyNode::class), Int.serializer())
    private val allMavenDependenciesSerializer: KSerializer<Map<MavenDependencyPlain, MavenDependencyIndex>> =
        MapSerializer(MavenDependencyPlain.serializer(), Int.serializer())
    private val allMavenDependencyConstraintsSerializer: KSerializer<Map<MavenDependencyConstraintPlain, DependencyNodeIndex>> =
        MapSerializer(MavenDependencyConstraintPlain.serializer(), Int.serializer())
    private val allResolutionConfigsSerializer: KSerializer<Map<ResolutionConfigPlain, ResolutionConfigIndex>> =
        MapSerializer(ResolutionConfigPlain.serializer(), Int.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "org.jetbrains.amper.dependency.resolution.DependencyGraphContextSerializer") {
        element("allDependencyNodes", allDependencyNodesSerializer.descriptor)
        element("allMavenDependencies", allMavenDependenciesSerializer.descriptor)
        element("allMavenDependencyConstraints", allMavenDependencyConstraintsSerializer.descriptor)
        element("allResolutionConfigs", allResolutionConfigsSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: DependencyGraphContext) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, allDependencyNodesSerializer, value.allDependencyNodes)
            encodeSerializableElement(descriptor, 1, allMavenDependenciesSerializer, value.allMavenDependencies)
            encodeSerializableElement(descriptor, 2, allMavenDependencyConstraintsSerializer, value.allMavenDependencyConstraints)
            encodeSerializableElement(descriptor, 3, allResolutionConfigsSerializer, value.allResolutionConfigs)
        }
    }

    override fun deserialize(decoder: Decoder): DependencyGraphContext {
        return decoder.decodeStructure(descriptor) {
            val graphContext = DependencyGraphContext()

            var allDependencyNodes: MutableMap<SerializableDependencyNode, DependencyNodeIndex>? = null
            var allMavenDependencies: MutableMap<MavenDependencyPlain, MavenDependencyIndex>? = null
            var allMavenDependencyConstraints: MutableMap<MavenDependencyConstraintPlain, MavenDependencyConstraintIndex>? = null
            var allResolutionConfigs: MutableMap<ResolutionConfigPlain, ResolutionConfigIndex>? = null

            try {
                DependencyGraphContext.currentGraphContext.set(graphContext)

                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> {
                            allDependencyNodes =
                                decodeSerializableElement(descriptor, 0, allDependencyNodesSerializer).toMutableMap()
                        }
                        1 -> {
                            allMavenDependencies =
                                decodeSerializableElement(descriptor, 1, allMavenDependenciesSerializer).toMutableMap()
                        }
                        2 -> {
                            allMavenDependencyConstraints =
                                decodeSerializableElement(descriptor, 2, allMavenDependencyConstraintsSerializer).toMutableMap()
                        }
                        3 -> {
                            allResolutionConfigs =
                                decodeSerializableElement(descriptor, 3, allResolutionConfigsSerializer).toMutableMap()
                        }
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index: $index")
                    }
                }
            } finally {
                DependencyGraphContext.currentGraphContext.set(null)
            }

            graphContext.allDependencyNodes.putAll(allDependencyNodes!!)
            graphContext.allMavenDependencies.putAll(allMavenDependencies!!)
            graphContext.allMavenDependencyConstraints.putAll(allMavenDependencyConstraints!!)
            graphContext.allResolutionConfigs.putAll(allResolutionConfigs!!)

            // Rebuild dependency nodes parentsRefs from childrenRefs
            graphContext.rebuildDependencyNodesParentRefs()

            graphContext
        }
    }
}

typealias DependencyNodeIndex = Int
typealias MavenDependencyIndex = Int
typealias MavenDependencyConstraintIndex = Int
typealias ResolutionConfigIndex = Int

fun currentGraphContext(): DependencyGraphContext = DependencyGraphContext.currentGraphContext.get()
    ?: error("Instance of DependencyGraphContext should be either explicitly passed to the constructor or presented in the dedicated ThreadLocal")

interface SerializableDependencyNode : DependencyNode {
    val parentsRefs: MutableSet<DependencyNodeReference>
    val childrenRefs: List<DependencyNodeReference>

    override val parents: MutableSet<DependencyNode>
}

@Serializable
abstract class SerializableDependencyNodeBase(
    @Transient
    private val graphContext: DependencyGraphContext = currentGraphContext()
) : SerializableDependencyNode {
    /**
     * Parent refs are not stored in a serialized graph.
     * It prevents every edge between dependency nodes being stored twice (child -> parent, parent -> child).
     * Instead, the 'child-> parent' reference is restored from the stored 'parent -> child' association
     * on graph deserialization.
     */
    @Transient
    final override val parentsRefs: MutableSet<DependencyNodeReference> = mutableSetOf()
    abstract override val childrenRefs: List<DependencyNodeReference>

    override val parents: MutableSet<DependencyNode> by lazy { parentsRefs.map { it.toNodePlain(graphContext) }.toMutableSet() }
    override val children: List<DependencyNode> by lazy { childrenRefs.map { it.toNodePlain(graphContext) } }

    override fun toString() = graphEntryName
}

/**
 * @return true if there is no path (even transitive) to the given root from the node via node's parents
 */
fun DependencyNode.isOrphan(root: DependencyNode, visited: MutableSet<DependencyNode> = mutableSetOf()): Boolean {
    if (this == root) return false // this is the root node
    if (this in visited) return true // already tried this node

    if (parents.isEmpty()) return true // no parents => orphan
    if (parents.contains(root)) return false // we reach the root

    visited.add(this)
    return parents.all { it.isOrphan(root, visited) }
}