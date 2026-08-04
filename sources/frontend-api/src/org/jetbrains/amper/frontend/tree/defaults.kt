/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.tree

import org.jetbrains.amper.frontend.api.Default
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.api.Trace
import org.jetbrains.amper.frontend.api.TraceableString
import org.jetbrains.amper.frontend.api.TraceableValue
import org.jetbrains.amper.frontend.contexts.DefaultContext
import org.jetbrains.amper.frontend.types.SchemaObjectDeclaration
import org.jetbrains.amper.frontend.types.SchemaType
import org.jetbrains.amper.frontend.types.SchemaValueWrappingInfo
import org.jetbrains.amper.stdlib.collections.associateByNotNull
import java.nio.file.Path

/**
 * Creates a default [KeyValue] for the given [property], if the property has the default value.
 */
internal fun createDefault(property: SchemaObjectDeclaration.Property): RefinedKeyValue? {
    val default = property.default ?: return null
    val value = default.toTreeValue(property.type, DefaultTrace, property.wrappingInfo)
    return RefinedKeyValue(DefaultTrace, value, property, DefaultTrace)
}

private fun createDefaultProperties(declaration: SchemaObjectDeclaration): Map<String, RefinedKeyValue> =
    declaration.properties.associateByNotNull(
        keySelector = SchemaObjectDeclaration.Property::name,
        valueTransform = ::createDefault,
    )

internal fun Default.toTreeValue(
    type: SchemaType,
    trace: Trace,
    wrappingInfo: SchemaValueWrappingInfo? = null,
): RefinedTreeNode = when (this) {
    is Default.Static -> toTreeValue(type, trace, wrappingInfo)
    is Default.NestedObject -> {
        check(type is SchemaType.ObjectType)
        check(type.declaration.properties.all { it.default != null }) {
            "All properties must have defaults for nested object default: ${type.declaration.displayName}"
        }
        RefinedMappingNode(createDefaultProperties(type.declaration), type.declaration, trace, TypeLevelDefaultContexts)
    }
    is Default.Reference -> ReferenceNode(
        referencedPath.map { TraceableString(it, trace) }, type, transform, trace, TypeLevelDefaultContexts
    )
}

fun Default.Static.toTreeValue(
    type: SchemaType,
    trace: Trace,
    wrappingInfo: SchemaValueWrappingInfo? = null,
): RefinedTreeNode {
    // TODO: Clean the `when` up here, because not all the values are possible now that the schema is generated.
    //  or even better - get rid of the untyped `Any` in the `Static.value` at all.

    // TODO: Remove this unwrapping madness when defaults are reworked
    //  Unwrap traceable is still needed because reference resolution creates static defaults
    val value = wrappingInfo?.unwrapValue?.invoke(value) ?: value.unwrapTraceable()
    return if (value == null) {
        check(type.isMarkedNullable) {
            "Null default is specified for non-nullable $type"
        }
        NullLiteralNode(trace, TypeLevelDefaultContexts)
    } else when (type) {
        is SchemaType.BooleanType -> BooleanNode(value as Boolean, trace, TypeLevelDefaultContexts)
        is SchemaType.EnumType -> EnumNode(
            when (value) {
                is Enum<*> -> value.name
                is String -> value
                else -> error("Invalid enum default: $value")
            },
            type.declaration, trace, TypeLevelDefaultContexts,
        )
        is SchemaType.IntType -> IntNode(value as Int, trace, TypeLevelDefaultContexts)
        is SchemaType.PathType -> PathNode(
            when (value) {
                is Path -> value
                else -> error("Invalid path default: $value")
            }, trace, TypeLevelDefaultContexts,
        )
        is SchemaType.StringType -> StringNode(
            value as String, type.semantics, trace, TypeLevelDefaultContexts,
        )
        is SchemaType.ListType -> {
            check(value is List<*>)
            check(value.isEmpty() || type.elementType is SchemaType.ScalarType) {
                "Non-empty lists as defaults are allowed only for lists with scalar element types"
            }
            val children = value.map {
                Default.Static(it).toTreeValue(
                    type.elementType,
                    trace,
                    (wrappingInfo as? SchemaValueWrappingInfo.List)?.elementInfo,
                )
            }
            RefinedListNode(children, trace, TypeLevelDefaultContexts)
        }
        is SchemaType.MapType -> {
            check(value == emptyMap<Nothing, Nothing>()) {
                "Only an empty map is permitted as a default for a map property. " +
                        "If there are cases, you'll need to extend the implementation here"
            }
            RefinedMappingNode(emptyMap(), declaration = null, trace, TypeLevelDefaultContexts)
        }
        is SchemaType.ObjectType, is SchemaType.VariantType -> {
            error("Static defaults for object types are not supported")
        }
        SchemaType.UndefinedType -> error("Defaults for UndefinedType are unexpected.")
    }
}

private fun Any?.unwrapTraceable(): Any? = if (this is TraceableValue<*>) value else this

private val TypeLevelDefaultContexts = listOf(DefaultContext.TypeLevel)
