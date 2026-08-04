/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.types

import org.jetbrains.amper.frontend.api.Trace

/**
 * Additional type info that is parallel to [SchemaType] that is required to correctly instantiate a value
 * on a [org.jetbrains.amper.frontend.api.SchemaNode]-level.
 */
sealed interface SchemaValueWrappingInfo {
    typealias WrapValueFunction = (Any?, Trace) -> Any?
    typealias UnwrapValueFunction = (Any?) -> Any?

    /**
     * A function that wraps the instantiated value into anything else to adhere to the type declared on the
     * [org.jetbrains.amper.frontend.api.SchemaNode]'s side.
     *
     * Examples:
     * - wrap into `TraceableValue`
     * - wrap into a value class
     */
    val wrapValue: WrapValueFunction?

    /**
     * Reverses traceable and value-class wrapping for defaults before they are converted to tree values.
     */
    val unwrapValue: UnwrapValueFunction?

    /**
     * Companion for [SchemaType], except for lists or maps.
     */
    data class Plain(
        override val wrapValue: WrapValueFunction,
        override val unwrapValue: UnwrapValueFunction? = null,
    ) : SchemaValueWrappingInfo

    /**
     * Companion for [SchemaType.ListType]
     */
    data class List(
        val elementInfo: SchemaValueWrappingInfo?,
        override val wrapValue: WrapValueFunction? = null,
        override val unwrapValue: UnwrapValueFunction? = null,
    ) : SchemaValueWrappingInfo

    /**
     * Companion for [SchemaType.MapType]
     */
    data class Map(
        val keyInfo: SchemaValueWrappingInfo?,
        val valueInfo: SchemaValueWrappingInfo?,
        override val wrapValue: WrapValueFunction? = null,
        override val unwrapValue: UnwrapValueFunction? = null,
    ) : SchemaValueWrappingInfo
}
