/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree.resolution

import org.jetbrains.amper.frontend.api.ResolvedReferenceTrace
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.Traceable
import org.jetbrains.amper.frontend.api.TransformedValueTrace
import org.jetbrains.amper.frontend.api.isDefault
import org.jetbrains.amper.frontend.tree.BooleanNode
import org.jetbrains.amper.frontend.tree.EnumNode
import org.jetbrains.amper.frontend.tree.ErrorNode
import org.jetbrains.amper.frontend.tree.IntNode
import org.jetbrains.amper.frontend.tree.ListNode
import org.jetbrains.amper.frontend.tree.MappingNode
import org.jetbrains.amper.frontend.tree.NullLiteralNode
import org.jetbrains.amper.frontend.tree.PathNode
import org.jetbrains.amper.frontend.tree.ReferenceNode
import org.jetbrains.amper.frontend.tree.RefinedListNode
import org.jetbrains.amper.frontend.tree.RefinedMappingNode
import org.jetbrains.amper.frontend.tree.RefinedTreeNode
import org.jetbrains.amper.frontend.tree.ResolvableNode
import org.jetbrains.amper.frontend.tree.StringNode
import org.jetbrains.amper.frontend.tree.TreeNode
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.render
import org.jetbrains.amper.stdlib.collections.joinToString

/**
 * Renders a human-readable representation of the "runtime type" of a given [tree node][value].
 *
 * NOTE: Collections' (map, list) type is erased,
 * so their effective runtime type is rendered as a sum-type of all the element types.
 *
 * @see org.jetbrains.amper.frontend.types.SchemaType.render
 */
fun renderTypeOf(value: TreeNode): String = when(value) {
    is NullLiteralNode -> "null"
    is ListNode -> {
        val allElementTypes = value.children.map(::renderTypeOf).distinct()
        "list [${allElementTypes.joinToString(" | ")}]"
    }
    is MappingNode -> value.declaration?.displayName ?: run {
        val allValueTypes = value.children.map { renderTypeOf(it.value) }.distinct()
        when (allValueTypes.size) {
            0 -> "map {}"
            1 -> "map {string : ${allValueTypes.single()}}"
            else -> "map {string : (${allValueTypes.joinToString(" | ")})}"
        }
    }
    is ErrorNode -> value.expectedType.render(includeSyntax = false)
    is BooleanNode -> "boolean"
    is EnumNode -> "enum ${value.declaration.displayName}"
    is IntNode -> "integer"
    is PathNode -> "path"
    is StringNode -> value.semantics.render()
    is ResolvableNode -> value.expectedType.render(includeSyntax = false)
}

@RequiresOptIn(
    message = "This API infers the type on the best effort basis. " +
            "Only use in cases were the precise type can't be tracked because it's physically not known. " +
            "Suited for diagnostics or suggestions",
    level = RequiresOptIn.Level.WARNING
)
annotation class DelicateSchemaTypeInferenceApi

/**
 * Tries to infer as precise a type as possible to which this node can be assigned.
 * If no well-formed type can be inferred, returns [SchemaType.UndefinedType], e.g., for `null` literals.
 *
 * @param nullFallbackType a type that is to be returned when encountering [NullLiteralNode]
 */
@DelicateSchemaTypeInferenceApi
fun TreeNode.inferPossibleExpectedTypeBestEffort(
    nullFallbackType: SchemaType = SchemaType.UndefinedType,
): SchemaType {
    require(nullFallbackType.isMarkedNullable)
    return when (this) {
        is NullLiteralNode -> nullFallbackType
        is ErrorNode -> expectedType
        is ResolvableNode -> expectedType
        is EnumNode -> declaration.toType()
        is BooleanNode -> SchemaType.BooleanType
        is IntNode -> SchemaType.IntType
        is PathNode -> SchemaType.PathType
        is StringNode -> SchemaType.StringType(semantics = semantics, canBeBlank = value.isBlank())
        is ListNode -> SchemaType.ListType(
            elementType = children.mapTo(mutableSetOf(), TreeNode::inferPossibleExpectedTypeBestEffort).singleOrNull()
                ?: SchemaType.UndefinedType
        )
        is MappingNode -> declaration?.toType() ?: SchemaType.MapType(
            valueType = children.mapTo(mutableSetOf()) { it.value.inferPossibleExpectedTypeBestEffort() }.singleOrNull()
                ?: SchemaType.UndefinedType
        )
    }
}

internal fun RefinedTreeNode.subtreeContainsResolvableNodes(): Boolean = when(this) {
    is RefinedListNode -> children.any { it.subtreeContainsResolvableNodes() }
    is RefinedMappingNode -> children.any { it.value.subtreeContainsResolvableNodes() }
    is ResolvableNode -> true
    else -> false
}

internal fun ReferenceNode.resolvedTrace(resolvedValue: Traceable) = ResolvedReferenceTrace(
    description = $$"$${if (trace.isDefault) "default, " else ""}from ${$${referencedPath.joinToString(".")}}",
    referenceTrace = trace,
    resolvedValue = resolvedValue,
)

internal fun ReferenceNode.transformedTrace(
    resolvedValue: Traceable,
    transform: ReferenceNode.Transform,
): Trace {
    check(trace.isDefault) { "Can't have transform in the non-default references" }
    return TransformedValueTrace(
        description = $$"default, based on ${$${referencedPath.joinToString(".")}}: $${transform.description}",
        sourceValue = resolvedValue,
    )
}

internal fun ErrorNode(
    unresolved: ResolvableNode,
    expectedType: SchemaType = unresolved.expectedType,
) = ErrorNode(expectedType, unresolved.trace, unresolved.contexts)