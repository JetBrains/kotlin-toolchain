/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.RunTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.processes.PrintToTerminalProcessOutputListener
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.NativeDesktopRunSettings
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import kotlin.io.path.pathString

class NativeRunTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    override val platform: Platform,
    override val buildType: BuildType,
    private val runSettings: NativeDesktopRunSettings,
    private val terminal: Terminal,
    private val processRunner: ProcessRunner,
) : RunTask {
    init {
        require(platform.isLeaf)
        require(platform.isDescendantOf(Platform.NATIVE))
    }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val compileTaskResult = dependenciesResult.filterIsInstance<NativeLinkTask.Result>().singleOrNull()
            ?: error("Could not find a single compile task in dependencies of $taskName")

        val executable = checkNotNull(compileTaskResult.linkedBinary) { "Executable must always be linked" }
        val programArgs = runSettings.programArgs

        return spanBuilder("native-run")
            .setAttribute("executable", executable.pathString)
            .setListAttribute("args", programArgs)
            .use { span ->
                val result = processRunner.runProcess(
                    workingDir = runSettings.workingDir,
                    command = listOf(executable.pathString) + programArgs,
                    span = span,
                    outputMode = ProcessOutputMode.Inherit, // safe because the progress widget is hidden for run tasks
                    input = ProcessInput.Inherit,
                )

                if (result.exitCode != 0) {
                    userReadableError("Process failed with exit code ${result.exitCode}", exitCode = result.exitCode)
                }

                EmptyTaskResult
            }
    }
}
