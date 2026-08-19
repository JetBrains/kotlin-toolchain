/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.options

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.option

internal const val ModuleOptionName = "--module"

/**
 * Accepts a module name, with proper completion.
 *
 * For commands that need multiple modules, use this option with the
 * [multiple][com.github.ajalt.clikt.parameters.options.multiple] modifier so it can be repeated by users.
 * For consistency, do not use comma-separated lists.
 */
internal fun ParameterHolder.moduleOption(
    vararg names: String = ["-m", ModuleOptionName],
    help: String,
    metavar: String = "<module>",
): OptionWithValues<String?, String, String> = option(
    *names,
    help = help,
    metavar = metavar,
    completionCandidates = ModuleCompletionCandidates,
)
