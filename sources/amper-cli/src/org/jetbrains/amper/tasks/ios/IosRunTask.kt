/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.engine.RunTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType

class IosRunTask(
    override val taskName: TaskName,
    override val platform: Platform,
    override val buildType: BuildType,
    override val module: AmperModule,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
) : RunTask {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val preparedRun = dependenciesResult.requireSingleDependency<IosPrepareDeviceForRunTask.Result>()
        terminal.println(terminal.theme.success("Starting the app"))
        if (platform.isIosSimulator) {
            processRunner.launchAppOnDevice(preparedRun.readyDeviceId, preparedRun.bundleId)
        } else {
            // Physical device
            processRunner.launchAppOnPhysicalDevice(preparedRun.readyDeviceId, preparedRun.bundleId)
        }
        return EmptyTaskResult
    }
}
