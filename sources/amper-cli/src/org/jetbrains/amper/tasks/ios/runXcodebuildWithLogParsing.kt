/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.amper.cli.terminal.filterAnsiCodes
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.PrintToTerminalProcessOutputListener
import org.jetbrains.amper.processes.output.FilteringProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.pipe.ProcessPipe
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.stdlib.url.CustomizableUrl
import org.jetbrains.amper.system.info.Arch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.Path
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isExecutable
import kotlin.io.path.pathString
import kotlin.io.path.setPosixFilePermissions

/**
 * Provisions and runs the `xcbeautify` tool, passing a [pipe][ProcessPipe] connected to it to the [block].
 *
 * @param userCacheRoot required to provision `xcbeautify` binary
 * @param terminal needed for printing and theming.
 * @param debugLogPrefix prefix to log the raw `xcodebuild` output with the debug level.
 * @param block expected to run an `xcodebuild` process using the pipe as its [ProcessOutputListener].
 */
context(sink: OperationEventSink)
suspend fun <R> runXcodebuildWithLogParsing(
    userCacheRoot: AmperUserCacheRoot,
    terminal: Terminal,
    debugLogPrefix: String = "xcodebuild",
    block: suspend (pipe: ProcessPipe) -> R,
): R = coroutineScope {
    val executable = prepareLogParsingUtility(userCacheRoot)
    val packageResolutionErrorWorkaround = PackageResolutionErrorWorkaround(terminal.theme)

    val pipe = ProcessPipe(
        includeStderr = true,
        eavesDroppingListener = LoggingProcessOutputListener(
            logger = logger,
            prefix = "$debugLogPrefix/out",
            stdErrPrefix = "$debugLogPrefix/err",
            stdoutLoggingLevel = Level.DEBUG,
            stderrLoggingLevel = Level.DEBUG,
        ) + packageResolutionErrorWorkaround.createRawLogListener(),
    )
    // Need to launch log parser in parallel
    val parserProcessJob = launch {
        runProcess(
            workingDir = Path("."),
            command = [
                executable.pathString,
                // By default, xcbeautify detects the environment and chooses the renderer based on it.
                // We want to process the output ourselves, so we enforce the terminal renderer.
                "--renderer", "terminal",
                "--disable-logging", // disable big version banner - we do it ourselves
                "--quiet",
            ],
            outputMode = ProcessOutputMode.listen(
                listener = packageResolutionErrorWorkaround.wrapBeautifiedLogPrinter(
                    printer = FilteringProcessOutputListener(
                        delegate = PrintToTerminalProcessOutputListener(terminal),
                        filterStdout = { line, _ -> line.filterAnsiCodes() != "Build Succeeded" },
                    ),
                ),
            ),
            input = pipe,
        )
    }

    val result = block(pipe)
    parserProcessJob.join()
    result
}

context(sink: OperationEventSink)
private suspend fun prepareLogParsingUtility(userCacheRoot: AmperUserCacheRoot): Path {
    val archString = when(Arch.current) {
        Arch.X64 -> "x86_64"
        Arch.Arm64 -> "arm64"
    }
    val version = XCBEAUTIFY_VERSION
    val archive = operationEventScope("downloading xcbeautify $version") {
        Downloader.downloadFileToCacheLocation(
            url = "$XCBEAUTIFY_DOWNLOAD_ROOT_URL/$version/xcbeautify-$version-$archString-apple-macosx.zip",
            userCacheRoot = userCacheRoot,
        )
    }
    val executable = extractFileToCacheLocation(archiveFile = archive, amperUserCacheRoot = userCacheRoot)
        .resolve("xcbeautify")
    if (!executable.isExecutable()) {
        val permissions = executable.getPosixFilePermissions()
        @Suppress("RETURN_VALUE_NOT_USED") // KT-86696
        executable.setPosixFilePermissions(permissions + PosixFilePermission.OWNER_EXECUTE)
    }
    return executable
}

/**
 * This is required because `xcbeautify` sometimes fails to parse multiline diagnostic messages.
 * See https://github.com/cpisciotta/xcbeautify/issues/105
 * We know about a single case like this that is important to us: KTC-5884.
 *
 * This class works by listening for the second error line after the particular error line in the raw log
 * and then inserting the line into the beautified output in the corresponding place.
 */
private class PackageResolutionErrorWorkaround(
    private val theme: Theme,
) {
    private val packageResolutionErrorDetails: MutableList<String> = []

    fun createRawLogListener() = object : ProcessOutputListener {
        // We assume that there is a single details line right after the message.
        var nextLineIsPackageResolutionDetails = false
        override fun onStdoutLine(line: String, pid: Long) {
            if (nextLineIsPackageResolutionDetails) {
                packageResolutionErrorDetails += line
                nextLineIsPackageResolutionDetails = false
            }
            if (PACKAGE_RESOLUTION_ERROR_TEXT in line) {
                nextLineIsPackageResolutionDetails = true
            }
        }
        override fun onStderrLine(line: String, pid: Long) = Unit
    }

    fun wrapBeautifiedLogPrinter(printer: ProcessOutputListener) = object : ProcessOutputListener by printer {
        // Lazy is needed to avoid concurrent mod exception.
        // NOTE: This works because `xcbeautify` buffers the whole build log and then prints it when the build is done.
        //       If this ever changes, then more complicated Flow-based approach would be required here.
        val iterator by lazy { packageResolutionErrorDetails.iterator() }

        override fun onStdoutLine(line: String, pid: Long) {
            printer.onStdoutLine(line, pid)
            if (PACKAGE_RESOLUTION_ERROR_TEXT in line && iterator.hasNext()) {
                printer.onStdoutLine(theme.danger(iterator.next()), pid)
            }
        }
    }

    companion object {
        private const val PACKAGE_RESOLUTION_ERROR_TEXT = "xcodebuild: error: Could not resolve package dependencies:"
    }
}

private val XCBEAUTIFY_DOWNLOAD_ROOT_URL = CustomizableUrl(
    "https://github.com/cpisciotta/xcbeautify/releases/download", "KOTLIN_TOOLCHAIN_XCBEAUTIFY_DOWNLOAD_ROOT_URL").url

private val logger = LoggerFactory.getLogger("runXcodebuildWithLogParsing")

