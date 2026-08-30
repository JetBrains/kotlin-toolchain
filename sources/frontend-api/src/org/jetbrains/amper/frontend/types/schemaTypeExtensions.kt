/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.types

import org.jetbrains.amper.frontend.api.SchemaNode
import org.jetbrains.amper.frontend.schema.SchemaMavenCoordinates
import kotlin.reflect.KClass

inline fun <reified T : SchemaNode> SchemaTypeDeclaration.isSameAs(): Boolean = isSameAs(T::class)

fun SchemaTypeDeclaration.isSameAs(`class`: KClass<out SchemaNode>): Boolean = qualifiedName == `class`.qualifiedName

/**
 * Builds a user-readable string representation of the type.
 *
 * @param includeSyntax if true, some grammar-like syntax will be included in the representation to describe
 * what values the type accepts.
 * @param onlyNested only affects the object types with the
 * [fromKeyAndTheRestNestedProperty][SchemaObjectDeclaration.getFromKeyAndTheRestNestedProperty],
 * when the [includeSyntax] is enabled. If set to `true` then only the "nested" part of the syntax is rendered.
 * `false` by default.
 */
fun SchemaType.render(
    includeSyntax: Boolean = true,
    onlyNested: Boolean = false,
): String = buildString {
    when (this@render) {
        is SchemaType.BooleanType -> {
            append("boolean")
            if (includeSyntax) {
                append(""" ( "true" | "false" )""")
            }
        }
        is SchemaType.IntType -> append("integer")
        is SchemaType.PathType -> append("path")
        is SchemaType.StringType -> {
            if (!canBeBlank) {
                append("non-blank ")
            }
            append(semantics.render())
        }
        is SchemaType.ListType -> append("sequence [${elementType.render(false)}]")
        is SchemaType.MapType -> append("mapping {${keyType.render(false)} : ${valueType.render(false)}}")
        is SchemaType.EnumType -> {
            // TODO: Introduce a public-name concept?
            append(declaration.displayName)
            if (includeSyntax) {
                declaration.entries.filter { !it.isOutdated && it.isIncludedIntoJsonSchema }.joinTo(
                    buffer = this,
                    separator = " | ",
                    prefix = " ( ",
                    postfix = " )",
                ) { it.schemaValue.quote() }
            }
        }
        is SchemaType.ObjectType -> {
            // e.g. Dependency ( string | { string: ( "exported" | DependencyScope | {..} } ) )
            val fromKeyProperty = declaration.getFromKeyAndTheRestNestedProperty()
            val onlyNestedInEffect = (fromKeyProperty != null || declaration.isExternalDependencyNotation) && onlyNested

            if (!onlyNestedInEffect) {
                // Skip the name, if rendering only nested part
                append(declaration.displayName)
                if (includeSyntax) {
                    append(" ")
                }
            }
            if (includeSyntax) {
                fun appendPossibleSyntax() {
                    val possibleSyntax = buildList {
                        declaration.getBooleanShorthand()?.let {
                            add(it.name.quote())
                        }
                        declaration.getSecondaryShorthand()?.let {
                            when(val type = it.type) {
                                is SchemaType.EnumType -> type.declaration.entries.forEach { entry ->
                                    // Add enum values "inline" for enum shorthand
                                    add(entry.schemaValue.quote())
                                }
                                else -> add(type.render())
                            }
                        }
                        add("{..}")
                    }
                    if (possibleSyntax.size == 1) {
                        append(possibleSyntax[0])
                    } else {
                        possibleSyntax.joinTo(
                            buffer = this,
                            prefix = if (onlyNestedInEffect) "" else "( ",
                            postfix = if (onlyNestedInEffect) "" else " )",
                            separator = " | ",
                        )
                    }
                }
                if (declaration.isExternalDependencyNotation && !onlyNested) {
                    if (declaration.properties.map { it.name }.sorted() == SchemaMavenCoordinates.properties.sorted()) {
                        append("( maven-coordinates )")
                    } else {
                        append("( maven-coordinates | maven-coordinates: ")
                        appendPossibleSyntax()
                        append(" )")   
                    }
                } else if (fromKeyProperty != null && !onlyNested) {
                    append("( ")
                    val fromKeyPropertyType = fromKeyProperty.type.render(false)
                    append(fromKeyPropertyType)
                    val otherProperties = declaration.properties.filterNot { it.isFromKeyAndTheRestNested }
                    if (otherProperties.isNotEmpty()) {
                        append(" | ").append(fromKeyPropertyType).append(": ")
                        appendPossibleSyntax()
                    }
                    append(" )")
                } else {
                    appendPossibleSyntax()
                }
            }
        }
        is SchemaType.VariantType -> {
            append(declaration.displayName)
            if (includeSyntax) {
                declaration.variantTree.joinTo(
                    buffer = this,
                    separator = " | ",
                    prefix = "( ",
                    postfix = " )",
                ) { it.declaration.displayName }
            }
        }
        SchemaType.UndefinedType -> append("<undefined-type>")
    }
    if (isMarkedNullable && this@render != SchemaType.UndefinedType) append(" | null")
}

fun SchemaType.StringType.Semantics?.render(): String = when (this) {
    SchemaType.StringType.Semantics.JvmMainClass -> "jvm-main-class"
    SchemaType.StringType.Semantics.PluginSettingsClass -> "plugin-settings-class"
    SchemaType.StringType.Semantics.MavenPlexusConfigXml -> "valid-xml"
    SchemaType.StringType.Semantics.TaskName -> "task-name"
    null -> "string"
}

val SchemaType.isDenotableInPlugins: Boolean get() = when (this) {
    is SchemaType.TypeWithDeclaration -> declaration.publicInterfaceReflectionName != null
    is SchemaType.ListType -> elementType.isDenotableInPlugins
    is SchemaType.MapType -> valueType.isDenotableInPlugins
    is SchemaType.BooleanType, is SchemaType.IntType,
    is SchemaType.PathType, is SchemaType.StringType,
        -> true
    SchemaType.UndefinedType -> false
}

private fun String.quote() = '"' + this + '"'