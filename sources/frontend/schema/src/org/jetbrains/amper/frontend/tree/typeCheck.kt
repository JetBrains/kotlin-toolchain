/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.types.SchemaType
import java.io.File
import kotlin.io.path.pathString

/**
 * Checks if [rhs] type can be assigned to this [SchemaType].
 *
 * This check considers:
 * - Nullability: A non-nullable type cannot be assigned a nullable type.
 * - Identity: The same types are always assignable.
 * - Hierarchy: Object types are assignable to [SchemaType.VariantType] if they are members of that variant.
 * - Implicit conversions: If [allowStringConversion] is true, [SchemaType.PathType], [SchemaType.EnumType],
 *   and [SchemaType.IntType] are considered assignable to [SchemaType.StringType] (provided [SchemaType.StringType.semantics] is null).
 * - Blank values: types that don't guarantee non-blank values are not assignable to a string type that
 *   [cannot be blank][SchemaType.StringType.canBeBlank], because there is no value to check here.
 *   Use [convertToStringNode] (or [isAssignableFrom]) with an actual node to check the value itself.
 * - Collections: [SchemaType.ListType] and [SchemaType.MapType] elements are checked invariantly (no implicit conversions
 *   for elements).
 *
 * @param rhs the type to check for assignability.
 * @param allowStringConversion whether to allow implicit conversions to [SchemaType.StringType].
 * @return true if [rhs] is assignable to this type.
 * @see cast
 */
@UsedInIdePlugin
fun SchemaType.isAssignableFrom(
    rhs: SchemaType,
    allowStringConversion: Boolean = true,
): Boolean {
    if (!isMarkedNullable && rhs.isMarkedNullable) {
        return false
    }

    return when (this) {
        SchemaType.UndefinedType -> true
        is SchemaType.ListType -> rhs is SchemaType.ListType
                && elementType.isAssignableFrom(rhs.elementType, allowStringConversion = false)
        is SchemaType.MapType -> rhs is SchemaType.MapType
                && keyType.isAssignableFrom(rhs.keyType, allowStringConversion = false)
                && valueType.isAssignableFrom(rhs.valueType, allowStringConversion = false)
        is SchemaType.ObjectType -> rhs is SchemaType.ObjectType && declaration == rhs.declaration
        is SchemaType.BooleanType -> rhs is SchemaType.BooleanType
        is SchemaType.EnumType -> rhs is SchemaType.EnumType && declaration == rhs.declaration
        is SchemaType.IntType -> rhs is SchemaType.IntType
        is SchemaType.PathType -> rhs is SchemaType.PathType
        is SchemaType.StringType -> when (rhs) {
            // Without an actual value at hand, we can't guarantee the non-blank contract, so a type that allows
            // blank values is not assignable to a type that doesn't.
            is SchemaType.StringType -> (semantics == null || semantics == rhs.semantics)
                    && (canBeBlank || !rhs.canBeBlank)
            // Enum entries and integers always convert to non-blank strings.
            // Paths can be converted to non-blank strings by using `.`, see the cast function.
            is SchemaType.PathType, is SchemaType.EnumType, is SchemaType.IntType ->
                allowStringConversion && semantics == null
            else -> false
        }
        is SchemaType.VariantType -> when (rhs) {
            is SchemaType.VariantType -> declaration == rhs.declaration
            is SchemaType.ObjectType -> rhs.declaration in declaration.variants
            else -> false
        }
    }
}

/**
 * Casts a [RefinedTreeNode] to this [SchemaType], possibly performing implicit conversions.
 *
 * This function handles:
 * - Simple types: returns the [node] if its type matches this [SchemaType].
 * - Objects and Variants: returns the [node] if its [RefinedMappingNode.declaration] matches this type or its member.
 * - Collections: returns the [node] if its elements are invariantly assignable to this type's elements.
 * - Implicit conversions: if [allowStringConversion] is true and this type is [SchemaType.StringType] (with null [SchemaType.StringType.semantics]),
 *   it can convert [PathNode], [EnumNode], and [IntNode] into a new [StringNode]. Empty paths are converted to `"."` if
 *   they are converted to a non-blank [StringNode], and blank paths are converted to `"./$pathString"`.
 * - Blank values: if this type is a string type that [cannot be blank][SchemaType.StringType.canBeBlank], the actual
 *   string value of the [node] (possibly converted) must not be blank. Nodes without a known value
 *   ([ResolvableNode], [ErrorNode]) are only checked at the type level.
 * - [ResolvableNode] and [ErrorNode]: checks if their expected types are assignable to this type.
 * - Nullability: returns the [NullLiteralNode] if this type is marked nullable.
 *
 * Used primarily during reference resolution and tree refinement to ensure nodes match the expected schema types.
 *
 * @param node the node to be cast.
 * @param allowStringConversion whether to perform implicit conversions to [SchemaType.StringType].
 * @return the original or converted [RefinedTreeNode], or `null` if the cast is not possible.
 * @see isAssignableFrom
 */
fun SchemaType.cast(
    node: RefinedTreeNode,
    allowStringConversion: Boolean = true,
): RefinedTreeNode? {
    if (node is ErrorNode) {
        // We allow passing an error node here as long as its expectedType is right
        return node.takeIf { isAssignableFrom(node.expectedType, allowStringConversion) }
    }

    if (node is ResolvableNode) {
        // For references/interpolation we just perform a type-check
        return node.takeIf { isAssignableFrom(node.expectedType, allowStringConversion) }
    }

    if (node is NullLiteralNode) {
        return node.takeIf { isMarkedNullable }
    }

    return when (this) {
        // No conversion - just type check
        SchemaType.UndefinedType -> node
        is SchemaType.ObjectType if node is RefinedMappingNode && declaration == node.declaration -> node
        is SchemaType.BooleanType if node is BooleanNode -> node
        is SchemaType.EnumType if node is EnumNode && declaration == node.declaration -> node
        is SchemaType.IntType if node is IntNode -> node
        is SchemaType.VariantType if node is RefinedMappingNode && node.declaration in declaration.variants -> node
        is SchemaType.PathType if node is PathNode -> node
        is SchemaType.StringType if node is StringNode && (semantics == null || semantics == node.semantics) ->
            node.takeIf { canBeBlank || node.value.isNotBlank() }
        is SchemaType.ListType if node is RefinedListNode -> node.takeIf {
            node.children.all { elementType.isAssignableFrom(it, allowStringConversion = false) }
        }
        is SchemaType.MapType if node is RefinedMappingNode && node.declaration == null -> node.takeIf {
            node.refinedChildren.values.all { kv ->
                valueType.isAssignableFrom(kv.value, allowStringConversion = false)
            }
        }
        // Conversion to string...
        is SchemaType.StringType if allowStringConversion -> when (node) {
            // ...from path
            is PathNode if semantics == null -> node.convertToStringNode(this)
            // ...from enum
            is EnumNode if semantics == null -> StringNode(node.schemaValue, null, node.trace, node.contexts)
            // ...from int
            is IntNode if semantics == null -> StringNode(node.value.toString(), null, node.trace, node.contexts)
            else -> null
        }
        else -> null
    }
}

private fun PathNode.convertToStringNode(targetType: SchemaType.StringType): StringNode {
    val rawPathString = value.pathString
    val stringValue = when {
        // An empty path denotes the current directory, so "." is equivalent
        !targetType.canBeBlank && rawPathString.isEmpty() -> "."
        // Blank (but non-empty) paths can denote blank file names. Prepending `./` gives an equivalent path.
        !targetType.canBeBlank && rawPathString.isBlank() -> "." + File.separatorChar + rawPathString
        else -> rawPathString
    }
    return StringNode(stringValue, null, trace, contexts)
}

/**
 * `true`, if [cast] can be successful, `false` otherwise.
 * @see cast
 */
@UsedInIdePlugin
fun SchemaType.isAssignableFrom(
    node: RefinedTreeNode,
    allowStringConversion: Boolean = true,
): Boolean {
    return cast(node, allowStringConversion) != null
}
