/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

import com.jetbrains.fus.reporting.schema.FieldValue
import com.jetbrains.fus.reporting.schema.ListEventField
import kotlin.enums.EnumEntries
import kotlin.enums.enumEntries

/**
 * An enum that can be used in FUS events.
 * This interface is not strictly required, but allows to streamline the `transform` of enum entries to strings.
 */
interface FusEnum<T : Enum<T>> {
    /**
     * The string representation of this enum value inside FUS events.
     */
    val fusId: String
}

internal inline fun <reified T> enumListField(
    name: String,
    description: String? = null,
): EnumListEventField<T> where T : FusEnum<T>, T : Enum<T> =
    enumListField<T>(name, description, transform = { it.fusId })

internal inline fun <reified T : Enum<T>> enumListField(
    name: String,
    description: String? = null,
    noinline transform: (T) -> String,
): EnumListEventField<T> = EnumListEventField(name, description, enumEntries<T>(), transform)

internal class EnumListEventField<T : Enum<T>>(
    override val name: String,
    override val description: String?,
    private val enumEntries: EnumEntries<T>,
    internal val transform: (T) -> String,
) : ListEventField<T>() {
    override val validationRule: List<String>
        get() = listOf("{enum:${enumEntries.map(transform).sorted().joinToString("|")}}")

    override fun getValue(value: List<T>): FieldValue = FieldValue.ofStringList(value.map(transform))
}

// There is unfortunately no `SetEventField<T>` yet
internal inline fun <reified T> enumSetField(
    name: String,
    description: String? = null,
): ListEventField<T> where T : FusEnum<T>, T : Enum<T> = enumListField<T>(name, description).uniqueAndUnordered()

// There is unfortunately no `SetEventField<T>` yet
internal inline fun <reified T : Enum<T>> enumSetField(
    name: String,
    description: String? = null,
    noinline transform: (T) -> String,
): ListEventField<T> = enumListField(name, description, transform).uniqueAndUnordered()

private fun <T : Enum<T>> EnumListEventField<T>.uniqueAndUnordered(): ListEventField<T> =
    uniqueAndUnordered(comparableKey = transform)
