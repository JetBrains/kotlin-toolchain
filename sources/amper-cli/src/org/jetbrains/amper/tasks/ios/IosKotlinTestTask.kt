/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.telemetry.setProcessResultAttributes
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.concurrency.StripedMutex
import org.jetbrains.amper.concurrency.withLock
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.TestTask
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.NativeTestRunSettings
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.TestResultsFormat
import org.jetbrains.amper.tasks.native.NativeLinkTask
import org.jetbrains.amper.tasks.native.StructuredNativeTestProcessOutputListener
import org.jetbrains.amper.tasks.native.toNativeTestExecutableArgs
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.test.PrettyRenderer
import org.jetbrains.amper.test.TeamCityRenderer
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import kotlin.io.path.absolutePathString

class IosKotlinTestTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    private val terminal: Terminal,
    private val runSettings: NativeTestRunSettings,
    override val platform: Platform,
    override val buildType: BuildType,
    private val processRunner: ProcessRunner,
) : TestTask {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val compileTaskResult = dependenciesResult.requireSingleDependency<NativeLinkTask.Result>()
        val workingDir = module.source.moduleDir
        val executable = compileTaskResult.linkedBinary
        if (executable == null) {
            logger.debug("No test binary was found for ${platform.pretty}, skipping test run")
            return EmptyTaskResult
        }
        val chosenDevice = processRunner.pickBestDevice() ?: error("No available device")

        DeviceLock.withLock(hash = chosenDevice.deviceId.hashCode()) {
            val spawnTestsCommand = listOf(
                XCRUN_EXECUTABLE,
                "simctl",
                "spawn",
                chosenDevice.deviceId,
                executable.absolutePathString(),
                "--",
            ) + runSettings.toNativeTestExecutableArgs()

            return spanBuilder("ios-kotlin-test")
                .setAttribute("executable", spawnTestsCommand.first())
                .setListAttribute("args", spawnTestsCommand.drop(1))
                .use { span ->
                    processRunner.bootAndWaitSimulator(chosenDevice)

                    val result = processRunner.runProcess(
                        workingDir = workingDir,
                        command = spawnTestsCommand,
                        span = span,
                        outputMode = ProcessOutputMode.listen(StructuredNativeTestProcessOutputListener(
                            renderer = when (runSettings.testResultsFormat) {
                                TestResultsFormat.Pretty -> PrettyRenderer(terminal)
                                TestResultsFormat.TeamCity -> TeamCityRenderer(terminal)
                            },
                        )),
                    )
                    span.setProcessResultAttributes(result)
                    if (result.exitCode != 0) {
                        userReadableError(
                            "Kotlin/Native $platform tests failed for module " +
                                    "'${module.userReadableName}' with exit code ${result.exitCode} (see errors above)"
                        )
                    }
                    processRunner.shutdownDevice(chosenDevice.deviceId)
                    EmptyTaskResult
                }
        }
    }

    private companion object {
        // Need to lock the device to avoid racy situations
        // when a parallel task shuts the simulator down while we still use it.
        val DeviceLock = StripedMutex()
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
