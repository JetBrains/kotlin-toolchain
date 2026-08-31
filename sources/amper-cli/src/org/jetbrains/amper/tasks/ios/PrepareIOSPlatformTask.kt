/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.amper.cli.events.operationEventScope
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.native.swiftpm.XcodebuildPlatform
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

class PrepareIOSPlatformTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val terminal: Terminal,
) : Task {
    class Result(
        val destinations: List<XcodeDestination>,
    ) : TaskResult

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result = context(executionContext.eventSink) {
        var destinations: List<XcodeDestination> = operationEventScope("Querying Xcode project destinations") {
            listXcodeDestinations(
                projectDir = module.xcodeProjectPath,
                schemeName = IosConventions.SCHEME_NAME,
            )
        }

        var missingAnyIosDevicePlaceholder = destinations.find { it.isIosWithError() }

        if (missingAnyIosDevicePlaceholder == null) {
            return Result(
                destinations = destinations.filterNot { it.isBuildOnly },
            )
        }

        logger.debug("Missing iOS platform signal: {}", missingAnyIosDevicePlaceholder)
        terminal.println("Missing iOS platform detected, downloading now (may take a while)")

        provisionIosPlatform()

        // The platform doesn't come online immediately;
        // so we wait and ensure the error disappeared from the destination list
        operationEventScope("Verifying the platform is ready", sink = executionContext.eventSink) {
            var retryCount = 0
            do {
                destinations = listXcodeDestinations(
                    projectDir = module.xcodeProjectPath,
                    schemeName = IosConventions.SCHEME_NAME,
                )
                missingAnyIosDevicePlaceholder = destinations.find { it.isIosWithError() }
                if (missingAnyIosDevicePlaceholder == null) {
                    break
                }
                if (retryCount++ > MAX_DESTINATION_RETRIES) {
                    userReadableError(
                        "No valid iOS destination is detected after downloading the iOS platform " +
                                "(${missingAnyIosDevicePlaceholder.error})"
                    )
                }
                delay(1.seconds)
            } while (true)
        }

        return Result(
            destinations = destinations.filterNot { it.isBuildOnly },
        )
    }

    companion object {
        /*
         Mutex is fine here because if multiple processes try to download the same thing simultaneously,
         they share the same internal download.
        */
        private val mutex = Mutex()
        private var downloaded = false

        private val logger = LoggerFactory.getLogger(javaClass)

        context(_: OperationEventSink)
        private suspend fun provisionIosPlatform() = mutex.withLock {
            if (downloaded) {
                return@withLock
            }
            val preDownloadedImagePath = System.getenv("KTC_IOS_PLATFORM_IMAGE_PATH")
            val [command, moniker] = if (preDownloadedImagePath != null) {
                logger.info("Using iOS Simulator image path: $preDownloadedImagePath")
                [
                    "xcrun", "xcodebuild",
                    "-importPlatform",
                    preDownloadedImagePath,
                ] to "importing the platform image"
            } else {
                [
                    "xcrun", "xcodebuild",
                    "-downloadPlatform",
                    "iOS",
                ] to "downloading the platform image"
            }
            val exit = operationEventScope(moniker) {
                runProcess(
                    workingDir = Path("."),
                    command = command,
                    outputMode = ProcessOutputMode.listen(object : ProcessOutputListener {
                        // TODO: Report progress
                        override fun onStdoutLine(line: String, pid: Long) = Unit
                        override fun onStderrLine(line: String, pid: Long) {
                            logger.error("[download:$pid] $line")
                        }
                    }),
                )
            }
            if (exit.exitCode != 0) {
                userReadableError("Unable to download iOS platform, see errors above")
            }
            downloaded = true
        }
    }
}

private const val MAX_DESTINATION_RETRIES = 20

private fun XcodeDestination.isIosWithError() = platform == XcodebuildPlatform.iOS && error != null
