/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.terminal

import com.github.ajalt.mordant.input.interactiveSelectList
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.widgets.SelectList

/**
 * Displays a list of items and allows the user to select one with the arrow keys and enter.
 */
internal fun <T : Any> Terminal.interactiveSelectList(
    items: List<T>,
    nameSelector: (T) -> String,
    descriptionSelector: ((T) -> String)? = null,
    title: String = "",
    filterable: Boolean = false,
): T? {
    val itemsByName = items.associateBy(nameSelector)
    val choice = interactiveSelectList {
        title(title)
        if (descriptionSelector != null) {
            entries(items.map { SelectList.Entry(nameSelector(it), descriptionSelector(it)) })
        } else {
            entries(itemsByName.keys)
        }
        filterable(filterable)
    } ?: return null
    return itemsByName[choice] ?: error("Item with name '$choice' not found")
}

internal fun Terminal.promptBoolean(
    question: String,
    default: Boolean? = null,
): Boolean? {
    val choicesInPrompt = when (default) {
        true -> " (Y/n)"
        false -> " (y/N)"
        null -> ""
    }
    val answer = prompt(
        prompt = "$question$choicesInPrompt",
        default = when (default) {
            true -> "y"
            false -> "n"
            null -> null
        },
        showChoices = false,
        showDefault = false,
        choices = listOf("y", "Y", "n", "N"),
    )
    return when (answer) {
        "y", "Y" -> true
        "n", "N" -> false
        else -> null
    }
}
