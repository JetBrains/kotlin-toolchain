/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.NonIdealDiagnostic
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ExitCode
import org.slf4j.Logger

/**
 * A [ProcessOutputListener][org.jetbrains.amper.processes.output.ProcessOutputListener] for the `cinterop` tool.
 *
 * Unlike `konanc`, `cinterop` never uses the Kotlin compiler message format (it doesn't go through the Kotlin CLI at
 * all, and never uses `MessageCollector`). Its failures are uncaught exceptions printed to stderr, which mostly relay
 * Clang diagnostics verbatim, and we don't want to parse Clang output here. This is why the output is captured as is,
 * and reported once the process has terminated:
 *  * if `cinterop` failed, the whole output is reported verbatim as a single error;
 *  * if `cinterop` succeeded, but skipped some non-importable modules (`skipNonImportableModules = true` in the def
 *    file), a single warning listing the skipped modules is reported, and the corresponding Clang diagnostics are
 *    logged at `DEBUG` level;
 *  * the location-less `warning: <text>` lines printed by `cinterop` itself (see [cinteropWarningRegex]) are reported
 *    as warnings;
 *  * anything else that is printed to stderr by a successful `cinterop` is logged at `WARN` level.
 *
 * @param reporter the reporter to report the problems to
 * @param moduleName the name of the module the cinterop is run for, for the reported [CompilerBuildProblem]s
 * @param logger the logger to use for the output that is not reported as build problems
 */
internal class CInteropOutputListener(
    private val reporter: ProblemReporter,
    private val moduleName: String,
    private val logger: Logger,
) : ErrorCountingOutputListener {

    private val stdoutLines = mutableListOf<String>()
    private val stderrLines = mutableListOf<String>()

    override var errorCount = 0
        private set

    override fun onStdoutLine(line: String, pid: Long) {
        logger.debug(line)
        stdoutLines.add(line)
    }

    override fun onStderrLine(line: String, pid: Long) {
        logger.debug(line)
        stderrLines.add(line)
    }

    override fun onStreamsFlushed(exitCode: ExitCode, pid: Long) {
        if (exitCode.value != 0) {
            reportFailure(exitCode)
            return
        }
        stderrLines.forEach { logger.warn(it) }
        stdoutLines.forEach { line ->
            cinteropWarningRegex.matchEntire(line)?.let { reportWarning(it.groups["text"]!!.value) }
        }
        val skippedModules = parseSkippedModules(stdoutLines)
        if (skippedModules.isNotEmpty()) {
            reportSkippedModules(skippedModules)
        }
    }

    @OptIn(NonIdealDiagnostic::class)
    private fun reportFailure(exitCode: ExitCode) {
        val output = (stderrLines + stdoutLines).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val message = buildString {
            append("cinterop failed with exit code $exitCode")
            if (output.isNotEmpty()) {
                append(":\n")
                output.joinTo(this, separator = "\n")
            }
        }
        errorCount++
        reporter.reportMessage(GlobalCompilerBuildProblem(moduleName = moduleName, message = message, level = Level.Error))
    }

    private fun reportSkippedModules(skippedModules: List<String>) {
        reportWarning(
            "cinterop skipped the following modules because they cannot be imported to Kotlin " +
                    "(for instance, C++ modules): ${skippedModules.joinToString()}. " +
                    "Run with --log-level=debug to see the underlying Clang diagnostics."
        )
    }

    @OptIn(NonIdealDiagnostic::class)
    private fun reportWarning(message: String) {
        reporter.reportMessage(GlobalCompilerBuildProblem(moduleName = moduleName, message = message, level = Level.Warning))
    }
}

/**
 * The only structured messages that `cinterop` prints on its own are location-less warnings produced by
 * `fun warn(msg: String) = println("warning: ${'$'}msg")` in `CommandLine.kt` (the `gen/jvm` package of the stub
 * generator in the Kotlin repo), e.g. about unsupported `-linker-option`s or an overridden package name.
 */
private val cinteropWarningRegex = Regex("""warning: (?<text>.+)""")

/**
 * When `skipNonImportableModules = true`, cinterop prints the Clang diagnostics of the modules it couldn't import
 * to stdout, wrapped in a `java.lang.Error` (see `getModulesASTFiles` in `ModuleSupport.kt` in the Kotlin repo):
 * ```
 * java.lang.Error: grpcpp: /path/to/endpoint_config.h:19:10: fatal error: 'string' file not found
 * grpc: /path/to/module.modulemap:2:17: error: umbrella header 'gRPC-Core-umbrella.h' not found
 * absl: /var/folders/T/4942355504760821608.m:1:9: fatal error: module 'absl' not found
 * ```
 * Each line is prefixed with the name of the skipped module.
 */
private const val SKIPPED_MODULES_BLOCK_PREFIX = "java.lang.Error: "
private val skippedModuleLineRegex = Regex("""(?<module>[A-Za-z_][A-Za-z0-9_.]*): .*""")

// cinterop's own `warning: ...` lines would otherwise look like diagnostics of a module named `warning`
private fun isSkippedModuleLine(line: String): Boolean =
    skippedModuleLineRegex.matches(line) && !cinteropWarningRegex.matches(line)

private fun parseSkippedModules(stdoutLines: List<String>): List<String> {
    val blockStart = stdoutLines.indexOfFirst { it.startsWith(SKIPPED_MODULES_BLOCK_PREFIX) }
    if (blockStart == -1) return []
    return stdoutLines.asSequence()
        .drop(blockStart)
        .mapIndexed { i, line -> if (i == 0) line.removePrefix(SKIPPED_MODULES_BLOCK_PREFIX) else line }
        .takeWhile { isSkippedModuleLine(it) }
        .map { skippedModuleLineRegex.matchEntire(it)!!.groups["module"]!!.value }
        .distinct()
        .toList()
}
