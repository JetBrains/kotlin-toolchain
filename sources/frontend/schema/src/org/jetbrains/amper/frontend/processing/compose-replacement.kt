/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.processing

import org.jetbrains.amper.frontend.schema.SchemaMavenCoordinates
import org.jetbrains.amper.frontend.tree.Changed
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TransformResult
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.tree.TreeTransformer
import org.jetbrains.amper.frontend.tree.copy
import org.jetbrains.amper.frontend.tree.copyWithValue
import org.jetbrains.amper.system.info.SystemInfo

context(systemInfo: SystemInfo)
internal fun MappingNode.substituteComposeOsSpecific() =
    ComposeOsSpecificSubstitutor(systemInfo).transform(this) as? MappingNode ?: this

internal class ComposeOsSpecificSubstitutor(systemInfo: SystemInfo) : TreeTransformer() {
    private val newArtifactId = "desktop-jvm-${systemInfo.familyArch}"

    private fun MappingNode.singleMatchingStringChild(key: String) =
        children.singleOrNull { it.key == key }?.takeIf { it.value is StringNode }

    override fun visitMap(node: MappingNode): TransformResult<TreeNode> {
        val artifactIdKeyValue = node.singleMatchingStringChild(SchemaMavenCoordinates::artifactId.name)
        val artifactIdNode = artifactIdKeyValue?.value as? StringNode ?: return super.visitMap(node)
        val artifactIdMatches = artifactIdNode.value == "desktop-jvm" || artifactIdNode.value == "desktop"
        val groupIdMatches = (node.singleMatchingStringChild(SchemaMavenCoordinates::groupId.name)?.value as? StringNode)
            ?.value == "org.jetbrains.compose.desktop"
        
        return if (artifactIdMatches && groupIdMatches) {
            val newGroupIdNode = artifactIdNode.copyWithValue(newArtifactId)
            val newGroupIdKeyValue = artifactIdKeyValue.copyWithValue(newGroupIdNode)
            Changed(node.copy(children = node.children - artifactIdKeyValue + newGroupIdKeyValue))
        } else super.visitMap(node)
    }
}
