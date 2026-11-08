/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintCompletionMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import org.jetbrains.amper.fus.schema.FusCommandResult
import org.jetbrains.amper.fus.schema.FusEvents
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the given [block] and reports its outcome to FUS as the result of the given [invokedCommand].
 *
 * [invokedCommand] may be null if only `kotlin` was called without any command.
 */
internal inline fun runAndReportFusCommandResult(invokedCommand: InvokedCommand?, block: () -> Unit) {
    try {
        block()
        reportFusCommandResult(invokedCommand)
    } catch (e: Throwable) {
        reportFusCommandResult(invokedCommand, e)
        throw e
    }
}

/**
 * Reports the outcome of the execution of [invokedCommand] to FUS, based on the [exception] that terminated it.
 * A null [exception] means the command completed successfully.
 */
private fun reportFusCommandResult(invokedCommand: InvokedCommand?, exception: Throwable? = null) {
    CliFusReporter.log(FusEvents.CommandResult) {
        command = invokedCommand?.name
        subcommands = invokedCommand?.subcommands ?: []
        result = exception.toFusCommandResult()
    }
}

/**
 * The [FusCommandResult] corresponding to the exception that terminated a command, or [FusCommandResult.Success] if the
 * command completed without throwing (null receiver).
 */
private fun Throwable?.toFusCommandResult(): FusCommandResult = when (this) {
    null -> FusCommandResult.Success
    // The shell completion script is printed by throwing this, which is a normal successful outcome.
    is PrintCompletionMessage -> FusCommandResult.Success
    // Clikt's Abort, and all the PrintMessage errors of this CLI, are thrown to stop the command gracefully when the
    // user cancels an interactive prompt. Nothing went wrong in those cases, so they are not failures.
    is CancellationException, is Abort, is ProgramResult, is PrintMessage -> FusCommandResult.Canceled
    // Clikt uses a zero status code for errors that only print something and exit normally, like the help message.
    is CliktError -> if (statusCode == 0) FusCommandResult.Success else FusCommandResult.Failure
    else -> FusCommandResult.Failure
}
