/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.swiftpm

import org.jetbrains.amper.frontend.api.CanBeReferenced
import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.tree.PathNode
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.RefinedTreeNode
import org.jetbrains.amper.frontend.tree.StringNode
import java.nio.file.Path

sealed class SwiftPMDependencySchema : SchemaNode() {
    abstract val packageName: String
    abstract val products: List<String>
    abstract val traits: List<String>

    class Remote : SwiftPMDependencySchema() {
        class RepoPathToPackageNameTransform : ReferenceNode.TransformFunction<String> {
            private fun inferPackageName(url: String) = url.split("/").last().split(".git").first()
            override fun transform(node: RefinedTreeNode): String = inferPackageName((((node as RefinedMappingNode).refinedChildren.get("value")!!.value as StringNode).value))
        }

        @CanBeReferenced
        val repository: Repository.Url by value<Repository.Url>()
        val version: Version.From by value<Version.From>()
        override val products: List<String> by value<List<String>>()
        override val packageName: String by referenceValue<String>(::repository, "package name", RepoPathToPackageNameTransform())
        override val traits: List<String> by value<List<String>>(emptyList())

        sealed class Version : SchemaNode() {
            class From : Version() {
                val value: String by value<String>()
            }
            // FIXME: Support al the other
            data class Exact constructor(val value: String) : Version()
            data class Range  constructor(val from: String, val through: String) : Version()
            data class Branch  constructor(val value: String) : Version()
            data class Revision  constructor(val value: String) : Version()
        }

        sealed class Repository : SchemaNode() {
            data class Id  constructor( val value: String) : Repository()
            class Url : Repository() {
                val value: String by value<String>()
            }
        }
    }

    class Local : SwiftPMDependencySchema() {
        @CanBeReferenced
        val path: Path by value<Path>()

        override val products: List<String> by value<List<String>>()

        class PathToPackageNameTransform : ReferenceNode.TransformFunction<String> {
            override fun transform(node: RefinedTreeNode): String = (node as PathNode).value.normalize().fileName.toString()
        }

        override val packageName: String by referenceValue<String>(::path, "package name", PathToPackageNameTransform())
        override val traits: List<String> by value<List<String>>(emptyList())
    }
}