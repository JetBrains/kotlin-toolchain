/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

/**
 * Prints the graph below the node using a Gradle-like output style.
 */
fun DependencyNode.prettyPrint(forMavenNode: MavenCoordinates? = null): String = buildString {
    val allMavenDepsKeys = distinctBfsSequence()
        .map { it.unwrap() }
        .filterIsInstance<MavenDependencyNode>()
        .groupBy { it.key }
    prettyPrint(this, allMavenDepsKeys, forMavenNode = forMavenNode)
}

private fun DependencyNode.prettyPrint(
    builder: StringBuilder,
    allMavenDepsKeys: Map<Key<MavenDependency>, List<MavenDependencyNode>>,
    indent: StringBuilder = StringBuilder(),
    visited: MutableSet<Pair<Key<*>, Any?>> = mutableSetOf(),
    addLevel: Boolean = false,
    forMavenNode: MavenCoordinates? = null
) {
    val thisUnwrapped = unwrap()
    builder.append(indent).append(graphEntryName)

    // key doesn't include a version on purpose,
    // but different nodes referencing the same MavenDependency result in the same dependencies
    // => add no need to distinguish those while pretty printing
    val seen = !visited.add(key to ((thisUnwrapped as? MavenDependencyNode)?.groupingGraphEntryKey() ?: (thisUnwrapped as? MavenDependencyConstraintNode)?.dependencyConstraint))
    if (seen && children.any { it.shouldBePrinted(allMavenDepsKeys, forMavenNode) }) {
        builder.append(" (*)")
    } else if (thisUnwrapped is MavenDependencyConstraintNode) {
        builder.append(" (c)")
    }
    builder.append('\n')
    if (seen || children.isEmpty()) {
        return
    }

    if (indent.isNotEmpty()) {
        indent.setLength(indent.length - 5)
        if (addLevel) {
            indent.append("│    ")
        } else {
            indent.append("     ")
        }
    }

    children
        .filter { it.shouldBePrinted(allMavenDepsKeys, forMavenNode) }
        .let { filteredNodes ->
            filteredNodes.forEachIndexed { i, it ->
                val addAnotherLevel = i < filteredNodes.size - 1
                if (addAnotherLevel) {
                    indent.append("├─── ")
                } else {
                    indent.append("╰─── ")
                }
                it.prettyPrint(builder, allMavenDepsKeys, indent, visited, addAnotherLevel, forMavenNode)
                indent.setLength(indent.length - 5)
            }
        }
}

private fun DependencyNode.shouldBePrinted(
    allMavenDepsKeys: Map<Key<MavenDependency>, List<MavenDependencyNode>>,
    forMavenNode: MavenCoordinates? = null
): Boolean = unwrap().let {
    it !is MavenDependencyConstraintNode || it.isConstraintAffectingTheGraph(
        allMavenDepsKeys,
        forMavenNode
    )
}

private fun MavenDependencyConstraintNode.isConstraintAffectingTheGraph(
    allMavenDepsKeys: Map<Key<MavenDependency>, List<MavenDependencyNode>>,
    forMavenNode: MavenCoordinates?
): Boolean =
    allMavenDepsKeys[this.key]?.let { affectedNode ->
        // If there is a dependency with the original version equal to the constraint version, then constraint is noop.
        affectedNode.none { dep ->
            dep.originalVersion == dep.dependency.version && dep.originalVersion == this.version.resolve()
        }
                &&
                // The constraint version is the same as the resulted dependency version,
                // and the original dependency version is different (⇒ constraint might have affected resolution)
                affectedNode.any { dep ->
                    this.version.resolve() == dep.dependency.version && dep.originalVersion != dep.dependency.version
                }
    } ?: forMavenNode?.let { group == it.groupId && module == it.artifactId }
    ?: false
