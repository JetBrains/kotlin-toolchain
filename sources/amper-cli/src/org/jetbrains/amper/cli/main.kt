/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parsers.CommandLineParser
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.cli.commands.LogsDirAwareInternalError
import org.jetbrains.amper.cli.commands.RootCommand
import org.jetbrains.amper.cli.logging.withoutConsoleLogging
import org.jetbrains.amper.cli.telemetry.TelemetryEnvironment
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Instant
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
                rootCommand.mainWithTelemetry(args)
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
private suspend fun SuspendingCliktCommand.mainWithTelemetry(args: Array<String>) {
    val command = this
    val result = spanBuilder("Parse CLI arguments").use { span ->
        CommandLineParser.parse(command, args.asList()).also { r ->
            span.setListAttribute("errors", r.invocation.errors.map { it.toString() })
        }
    }
    spanBuilder("Run command")
        .setAttribute("command", result.invocation.command.commandName)
        .setListAttribute("subcommands", result.invocation.subcommandInvocations.map { it.command.commandName })
        .use {
            try {
                CommandLineParser.run(result.invocation) { it.run() }
            } catch (e: CliktError) {
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
            }
        }
}

/**
 * An exception used to convey that we want to exit the current process but still close the telemetry spans properly.
 * This is necessary because exitProcess() bypasses any finally block and thus prevents telemetry from completing.
 */
private class ExitProcessButCloseTelemetrySpansException(val exitCode: Int) : RuntimeException()

private fun handleUserError(e: UserReadableError): Nothing {
    printUserError(e.message, e.cause)
    // See `resultsOrThrowCombinedError`
    e.suppressedExceptions
        .filterIsInstance<UserReadableError>()
        .forEach { printUserError(it.message, it.cause) }
    exitProcess(e.exitCode)
}

private fun printUserError(message: String, cause: Throwable?) {
    printRedToStderr("\nERROR: $message")
    withoutConsoleLogging {
        logger.error(message, cause)
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
