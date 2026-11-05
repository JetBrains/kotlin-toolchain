/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.github.ajalt.clikt.parsers.CommandInvocation

/**
 * A command that the user invoked on the command line, with its nested subcommands.
 */
internal data class InvokedCommand(
    /**
     * The name of the command, such as `show` in `kotlin show modules`.
     */
    val name: String,
    /**
     * The names of the nested subcommands of [name], such as `modules` in `kotlin show modules`.
     */
    val subcommands: List<String>,
)

/**
 * Extracts the command that the user asked for from the invocation of the root `kotlin` command, or returns null if
 * the CLI was invoked without any command.
 */
internal fun CommandInvocation<*>.toInvokedCommand(): InvokedCommand? {
    // The root command is the 'kotlin' CLI itself, so the command the user asked for is its first subcommand.
    val commandInvocation = subcommandInvocations.firstOrNull() ?: return null
    return InvokedCommand(
        name = commandInvocation.command.commandName,
        subcommands = commandInvocation.subcommandInvocations.map { it.command.commandName },
    )
}
