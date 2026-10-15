/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * @param fileCondition is a predicate that is applied to each [DependencyFile] belonging to the dependency node.
 * If the predicate returns true, then the file path is included in the returned list.
 * If the predicate returns false, then the file path is excluded from the returned list.
 * By default, all files are included.
 *
 * @param nodeBlock is a callback applied to each dependency node in the graph after it visited on graph traversal.
 *
 * @return paths of all artifacts belonging to dependency graph nodes.
 */
fun DependencyNode.dependencyPaths(
    fileCondition: DependencyFile.() -> Boolean = { true },
    nodeBlock: (DependencyNode) -> Unit = {}
): List<Path> {
    val files = mutableSetOf<Path>()
    for (node in distinctBfsSequence()) {
        if (node is MavenDependencyNode) {
            node.dependency
                .files()
                .filter { it.fileCondition() }
                .mapNotNull { it.path?.takeIf { it.exists() } }
                .let {
                    files.addAll(it)
                }
        }
        nodeBlock(node)
    }
    return files.toList()
}

/**
 * @return paths of the archives with KMP resources of all dependency graph nodes.
 *
 * Note: those archives are not a part of [dependencyPaths], they are not meant to be put on a classpath,
 * see [MavenDependency.kmpResourcesFiles].
 */
fun DependencyNode.kmpResourcesPaths(): List<Path> {
    val files = mutableSetOf<Path>()
    for (node in distinctBfsSequence()) {
        if (node is MavenDependencyNode) {
            node.dependency
                .kmpResourcesFiles
                .mapNotNull { it.path?.takeIf { it.exists() } }
                .let {
                    files.addAll(it)
                }
        }
    }
    return files.toList()
}
