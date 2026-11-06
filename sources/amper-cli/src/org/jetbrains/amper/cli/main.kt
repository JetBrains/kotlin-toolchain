/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintCompletionMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parsers.CommandLineParser
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.util.logging.*
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.cli.analytics.CliFusReporter
import org.jetbrains.amper.cli.analytics.InvokedCommand
import org.jetbrains.amper.cli.analytics.toInvokedCommand
import org.jetbrains.amper.cli.commands.LogsDirAwareInternalError
import org.jetbrains.amper.cli.commands.RootCommand
import org.jetbrains.amper.cli.logging.withoutConsoleLogging
import org.jetbrains.amper.cli.telemetry.TelemetryEnvironment
import org.jetbrains.amper.cli.terminal.createMordantTerminal
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.fus.schema.FusCommandResult
import org.jetbrains.amper.fus.schema.FusEvents
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("main")

suspend fun main(args: Array<String>) {
    try {
        // We don't use RuntimeMXBean.startTime because it might give incorrect results if the system time changes.
        // The uptime value used to have a bug because of this, but now uses a more precise OS-provided value.
        // See https://bugs.openjdk.org/browse/JDK-6523160
        val jvmUptimeMillisAtMainStart = ManagementFactory.getRuntimeMXBean().uptime
        val mainStartTime = Instant.now()
        val jvmStartTime = mainStartTime.minusMillis(jvmUptimeMillisAtMainStart)

        val defaultCacheRoot = AmperUserCacheRoot.fromCurrentUserResult().unwrap()
        val telemetrySetupStartTime = Instant.now()
        TelemetryEnvironment.setup(defaultCacheRoot)
        spanBuilder("Root")
            .setStartTimestamp(jvmStartTime)
            .setListAttribute("args", args.toList())
            .setListAttribute("jvm-args", ManagementFactory.getRuntimeMXBean().inputArguments)
            .use {
                // we add spans here to represent stuff that happened before telemetry was initialized
                spanBuilder("JVM startup")
                    .setStartTimestamp(jvmStartTime)
                    .startSpan()
                    .end(mainStartTime)

                spanBuilder("Setup telemetry")
                    .setStartTimestamp(mainStartTime)
                    .use {
                        spanBuilder("Find default cache directory")
                            .setStartTimestamp(mainStartTime)
                            .startSpan()
                            .end(telemetrySetupStartTime)
                    }

                val rootCommand = spanBuilder("Initialize CLI command definitions").use {
                    RootCommand()
                }
                CliFusReporter.use {
                    rootCommand.mainWithTelemetry(args)
                }
            }
    } catch (e: ExitProcessButCloseTelemetrySpansException) {
        exitProcess(e.exitCode)
    } catch (e: UserReadableError) {
        handleUserError(e)
    } catch (e: LogsDirAwareInternalError) {
        val effectiveException = e.cause
        if (effectiveException is UserReadableError) {
            handleUserError(effectiveException)
        } else {
            printInternalError(effectiveException, e.logsDir)
            exitProcess(1)
        }
    } catch (e: Exception) {
        printInternalError(e, logsDir = null)
        exitProcess(1)
    }
}

/**
 * Parses command line arguments and runs this command.
 */
// This implementation is inlined from CoreSuspendingCliktCommand.main() and CommandLineParser.main()
// to isolate the parsing of CLI arguments for telemetry purposes.
private suspend fun RootCommand.mainWithTelemetry(args: Array<String>) {
    val command = this
    val result = spanBuilder("Parse CLI arguments").use { span ->
        CommandLineParser.parse(command, args.asList()).also { r ->
            span.setListAttribute("errors", r.invocation.errors.map { it.toString() })
        }
    }
    val invokedCommand = result.invocation.toInvokedCommand()
    command.invokedCommand = invokedCommand

    spanBuilder("Run command")
        .setAttribute("command", invokedCommand?.name ?: "(root)")
        .setListAttribute("subcommands", invokedCommand?.subcommands ?: [])
        .use {
            try {
                coroutineScope {
                    command.commandChainExecutionScope = this
                    CommandLineParser.run(result.invocation) { it.run() }
                }
                reportFusCommandResult(invokedCommand)
            } catch (e: CliktError) {
                reportFusCommandResult(invokedCommand, e)
                command.echoFormattedHelp(e)

                // We don't want to throw the exception from the "run command" span unconditionally because some errors
                // are simply used as a short-circuiting mechanism (like to print the help or abort gracefully).
                // If we threw when the exit code is 0, the telemetry span would be incorrectly marked as failed.
                // It's ok to do nothing here, because nothing else happens after this function: we just complete all
                // the spans and exit the process normally (thus with exit code 0).
                if (e.statusCode != 0) {
                    // We don't use the exitProcess() function like in the original CommandLineParser.main() function
                    // because it bypasses any finally blocks, and thus prevents our telemetry spans from completing.
                    throw ExitProcessButCloseTelemetrySpansException(e.statusCode)
                }
            } catch (e: Throwable) {
                reportFusCommandResult(invokedCommand, e)
                throw e
            }
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
    is CancellationException, is Abort, is PrintMessage -> FusCommandResult.Canceled
    // Clikt uses a zero status code for errors that only print something and exit normally, like the help message.
    is CliktError -> if (statusCode == 0) FusCommandResult.Success else FusCommandResult.Failure
    else -> FusCommandResult.Failure
}

/**
 * An exception used to convey that we want to exit the current process but still close the telemetry spans properly.
 * This is necessary because exitProcess() bypasses any finally block and thus prevents telemetry from completing.
 */
private class ExitProcessButCloseTelemetrySpansException(val exitCode: Int) : RuntimeException()

private fun handleUserError(e: UserReadableError): Nothing {
    // See `resultsOrThrowCombinedError`
    val otherException = e.suppressedExceptions.filterIsInstance<UserReadableError>()
    try {
        val terminal = createMordantTerminal()
        terminal.printUserError(e)
        otherException.forEach { terminal.printUserError(it) }
    } catch (terminalException: Exception) {
        logger.error(e)
        otherException.forEach { logger.error(it) }
        withoutConsoleLogging {
            logger.error("Failed to create terminal", terminalException)
        }
    }
    exitProcess(e.exitCode)
}

private fun Terminal.printUserError(e: UserReadableError) {
    if (e.isMarkdown) {
        print(theme.danger("\nERROR: "), stderr = true)
        println(Markdown(e.message), stderr = true)
    } else {
        println(theme.danger("\nERROR: ${e.message}"), stderr = true)
    }
    withoutConsoleLogging {
        logger.error(e.message, e.cause)
    }
}

private fun printInternalError(e: Throwable, logsDir: Path?) {
    // Note: we do not rely on console logging here, because the internal error could have occurred before it's set up
    printRedToStderr("\nInternal error:")
    e.printStackTrace()

    val message = buildString {
        append("\nPlease file a bug report at https://youtrack.jetbrains.com/newIssue?project=KTC")
        if (logsDir != null) {
            appendLine(", and attach your logs from $logsDir")
        }
    }
    printRedToStderr(message)

    withoutConsoleLogging {
        logger.error("Internal error:", e)
    }
}

private fun printRedToStderr(message: String) {
    val terminal = Terminal()
    val errorStyle = terminal.theme.danger
    terminal.println(errorStyle(message), stderr = true)
}
