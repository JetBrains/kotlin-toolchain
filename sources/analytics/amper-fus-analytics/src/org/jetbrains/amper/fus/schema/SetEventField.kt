/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

import com.jetbrains.fus.reporting.schema.FieldValue
import com.jetbrains.fus.reporting.schema.ListEventField

internal class SetEventField<T>(
    private val delegate: ListEventField<T>,
    private val comparableKey: (T) -> String,
) : ListEventField<T>() {
    override val name: String get() = delegate.name
    override val description: String? get() = delegate.description
    override val validationRule: List<String> get() = delegate.validationRule

    // We sort values consistently so that the order doesn't matter in the events (two events that originally had
    // different order of values will appear as having the same values)
    override fun getValue(value: List<T>): FieldValue? {
        val uniqueAndSorted = value.distinctBy { comparableKey(it) }.sortedBy { comparableKey(it) }
        return delegate.getValue(uniqueAndSorted)
    }
}

internal fun <T : Enum<T>> ListEventField<T>.uniqueAndUnordered(comparableKey: (T) -> String): ListEventField<T> =
    SetEventField(this, comparableKey)
