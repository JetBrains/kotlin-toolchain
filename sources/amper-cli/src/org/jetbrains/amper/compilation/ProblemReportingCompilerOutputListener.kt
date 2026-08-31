/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerMessage
import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerMessage.Severity
import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerOutputItem
import org.jetbrains.amper.kotlin.compiler.messages.KotlinCompilerOutputParser
import org.jetbrains.amper.kotlin.compiler.messages.UnrecognizedOutputLine
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.slf4j.Logger
import java.nio.file.Path
import org.slf4j.event.Level as LogLevel

/**
 * A [ProcessOutputListener] that parses the messages of a Kotlin CLI compiler (`konanc`, `cinterop`) and reports the
 * diagnostics to the given [reporter] as [CompilerBuildProblem]s, just like
 * [ProblemReportingCompilerMessageRenderer] does for compilations run through the Kotlin Build Tools API.
 *
 * The CLI compilers print all their warnings/errors to stderr, so the stream cannot be used to tell how important a
 * message is. This is why the severity of each message is parsed instead: warnings and errors are reported as build
 * problems, while less important messages are simply logged with a matching log level.
 *
 * Lines that are not part of a compiler message are logged based on the stream they come from (stdout at
 * `INFO` level, and stderr at `ERROR` level), because they usually come from other tools spawned by the compiler
 * (linkers, C compilers) and are mostly relevant when the compilation fails.
 *
 * @param reporter the reporter to report the compiler diagnostics to
 * @param moduleName the name of the module being compiled, for the reported [CompilerBuildProblem]s
 * @param workingDir the working directory of the compiler process, used to resolve the paths of the messages (the
 *   compiler may print paths relative to its working directory)
 * @param logger the logger to use for the messages that are not reported as build problems
 */
internal class ProblemReportingCompilerOutputListener(
    private val reporter: ProblemReporter,
    private val moduleName: String,
    private val workingDir: Path,
    private val logger: Logger,
) : ProcessOutputListener {

    private val stdoutParser = KotlinCompilerOutputParser { message ->
        handle(message, unrecognizedLineLevel = LogLevel.INFO)
    }
    private val stderrParser = KotlinCompilerOutputParser { message ->
        handle(message, unrecognizedLineLevel = LogLevel.ERROR)
    }

    /**
     * The number of errors reported to the [reporter] so far.
     */
    var errorCount = 0
        private set

    override fun onStdoutLine(line: String, pid: Long) {
        stdoutParser.consumeLine(line)
    }

    override fun onStderrLine(line: String, pid: Long) {
        stderrParser.consumeLine(line)
    }

    override fun onStreamsFlushed(exitCode: Int, pid: Long) {
        stderrParser.flush()
        stdoutParser.flush()
    }

    private fun handle(item: KotlinCompilerOutputItem, unrecognizedLineLevel: LogLevel) {
        when (item) {
            is UnrecognizedOutputLine -> logger.atLevel(unrecognizedLineLevel).log(item.text)
            is KotlinCompilerMessage -> handleMessage(item)
        }
    }

    private fun handleMessage(message: KotlinCompilerMessage) {
        val level = message.severity.toProblemLevel()
        if (level == null) {
            logger.atLevel(message.severity.toLogLevel()).log(message.text)
            return
        }
        if (level == Level.Error) {
            errorCount++
        }
        reporter.reportMessage(message.toBuildProblem(level))
    }

    @OptIn(NonIdealDiagnostic::class)
    private fun KotlinCompilerMessage.toBuildProblem(level: Level): CompilerBuildProblem {
        val location = location
            ?: return GlobalCompilerBuildProblem(moduleName = moduleName, message = text, level = level)
        val column = location.columnStart ?: 1
        return FileCompilerBuildProblem(
            moduleName = moduleName,
            message = text,
            level = level,
            source = CompilerBuildProblemSource(
                // The compiler may print paths relative to its own working directory
                file = workingDir.resolve(location.path),
                line = location.line,
                column = column,
                // The CLI compiler only prints the start of the range, and materializes the end with carets
                lineEnd = location.line,
                columnEnd = location.columnEnd ?: column,
            ),
        )
    }
}

/**
 * The [Level] to report a message of this severity as, or null if messages of this severity should not be reported as
 * build problems.
 */
private fun Severity.toProblemLevel(): Level? = when (this) {
    // The compiler prints its internal errors with the 'exception' severity
    Severity.Exception,
    Severity.Error,
        -> Level.Error
    Severity.Warning -> Level.Warning
    // Do not report INFO and DEBUG-like messages as problems, they are just logs about the compilation itself
    // (see also the comment in ProblemReportingCompilerMessageRenderer)
    Severity.Info,
    Severity.Logging,
    Severity.Output,
        -> null
}

private fun Severity.toLogLevel(): LogLevel = when (this) {
    Severity.Exception, Severity.Error -> LogLevel.ERROR
    Severity.Warning -> LogLevel.WARN
    Severity.Info, Severity.Output -> LogLevel.INFO
    Severity.Logging -> LogLevel.DEBUG
}
