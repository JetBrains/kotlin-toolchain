/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.types

sealed interface SchemaType {
    val isMarkedNullable: Boolean

    /**
     * Type for [org.jetbrains.amper.frontend.tree.ScalarNode]
     */
    sealed interface ScalarType : SchemaType
    
    sealed interface TypeWithDeclaration : SchemaType {
        val declaration: SchemaTypeDeclaration
    }

    /**
     * Type for [org.jetbrains.amper.frontend.tree.StringInterpolationNode]
     */
    sealed interface StringInterpolatableType : SchemaType

    /**
     * Type for [org.jetbrains.amper.frontend.tree.MappingNode]
     */
    sealed interface MapLikeType : SchemaType

    data class BooleanType(
        override val isMarkedNullable: Boolean = false,
    ) : ScalarType

    data class IntType(
        override val isMarkedNullable: Boolean = false,
        val knownValues: Set<Int>? = null,
    ) : ScalarType

    data class StringType(
        override val isMarkedNullable: Boolean = false,
        val knownValues: Set<String>? = null,
        val semantics: Semantics? = null,
        /**
         * Whether values of this type may be blank (empty, or just whitespace).
         */
        val canBeBlank: Boolean = true,
    ) : ScalarType, StringInterpolatableType {
        enum class Semantics {
            /**
             * FQN that references a class used as an entrypoint for JVM.
             */
            JvmMainClass,

            /**
             * FQN that references a class marked with `@Configurable` annotation used as plugin settings class.
             */
            PluginSettingsClass,

            /**
             * Valid XML block used explicitly for configuring
             * a Maven Plexus component that is passed to the Mojo execution
             * and doesn't have a type safe description.
             */
            MavenPlexusConfigXml,

            /**
             * String that references a task name registered in the plugin.
             */
            TaskName,
        }
    }

    data class PathType(
        override val isMarkedNullable: Boolean = false,
    ) : ScalarType, StringInterpolatableType

    data class EnumType(
        override val declaration: SchemaEnumDeclaration,
        override val isMarkedNullable: Boolean = false,
    ) : ScalarType, TypeWithDeclaration

    data class ObjectType(
        override val declaration: SchemaObjectDeclaration,
        override val isMarkedNullable: Boolean = false,
    ) : TypeWithDeclaration, MapLikeType

    data class VariantType(
        override val declaration: SchemaVariantDeclaration,
        override val isMarkedNullable: Boolean = false,
    ) : TypeWithDeclaration

    data class ListType(
        val elementType: SchemaType,
        override val isMarkedNullable: Boolean = false,
    ) : SchemaType

    data class MapType(
        val valueType: SchemaType,
        val keyType: StringType = StringType,
        override val isMarkedNullable: Boolean = false,
    ) : SchemaType, MapLikeType

    /**
     * A special **non-denotable** type that would accept any value.
     * Used internally in places where the concrete type can't be known due to an invalid user input,
     * but where we still want to proceed "normally" on the best effort basis/to not issue induced errors.
     */
    data object UndefinedType : SchemaType, StringInterpolatableType {
        override val isMarkedNullable: Boolean get() = true
    }

    companion object {
        val StringType = StringType()
        val PathType = PathType()
        val BooleanType = BooleanType()
        val IntType = IntType()
    }
}