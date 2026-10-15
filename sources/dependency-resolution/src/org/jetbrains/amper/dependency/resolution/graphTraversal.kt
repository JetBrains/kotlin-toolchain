/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import java.util.*

/**
 * Returns a sequence of distinct nodes using BFS starting at (and including) this node.
 *
 * The given [childrenPredicate] can be used to skip parts of the graph.
 * If [childrenPredicate] is false for a node, the node is skipped and will not appear in the sequence.
 * The subgraph of the skipped node is also not traversed, so these descendant nodes won't be in the sequence unless
 * they are reached via some other node.
 * Using [childrenPredicate] is, therefore, different from filtering the resulting sequence after the fact.
 *
 * The nodes are distinct in terms of referential identity, which is enough to eliminate duplicate "requested"
 * dependency triplets. This does NOT eliminate nodes that requested the same dependency in different versions,
 * even though conflict resolution should make them point to the same dependency version internally eventually.
 *
 * The returned sequence is guaranteed to be finite, as it prunes the graph when encountering duplicates (and thus cycles).
 */
fun DependencyNode.distinctBfsSequence(
    childrenPredicate: (child: DependencyNode, parent: DependencyNode) -> Boolean = { _,_ -> true }
): Sequence<DependencyNode> =
    bfsSequence(includeDuplicates = false, childrenPredicate)

/**
 * Returns a sequence of nodes using BFS starting at (and including) this node.
 * If the same node appears several times in the graph, its subgraph is traversed once only.
 *
 * The given [childrenPredicate] can be used to skip parts of the graph.
 * If [childrenPredicate] is false for a node, the node is skipped and will not appear in the sequence.
 * The subgraph of the skipped node is also not traversed, so these descendant nodes won't be in the sequence unless
 * they are reached via some other node.
 * Using [childrenPredicate] is, therefore, different from filtering the resulting sequence after the fact.
 *
 * Flag [includeDuplicates] specifies whether to include nodes that appear several times in the graph in the resulting sequence
 * or limit itself including the first occurrence only (which is by default).
 *
 * The nodes are distinct in terms of referential identity, which is enough to eliminate duplicate "requested"
 * dependency triplets. This does NOT eliminate nodes that requested the same dependency in different versions,
 * even though conflict resolution should make them point to the same dependency version internally eventually.
 *
 * The returned sequence is guaranteed to be finite, as it prunes the graph when encountering duplicates (and thus cycles).
 */
fun DependencyNode.bfsSequence(
    includeDuplicates: Boolean = false,
    childrenPredicate: (child: DependencyNode, parent: DependencyNode) -> Boolean = { _,_ -> true }
): Sequence<DependencyNode> = sequence {
    val queue = LinkedList(listOf(this@bfsSequence))
    val visited = mutableSetOf<DependencyNode>()
    while (queue.isNotEmpty()) {
        val node = queue.remove()
        if (visited.add(node)) {
            yield(node)
            queue.addAll(node.children.filter { it !in visited && childrenPredicate(it, node) })
        } else {
            if (includeDuplicates) {
                yield(node)
            }
        }
    }
}