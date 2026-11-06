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
import org.jetbrains.amper.tasks.MobileRunSettings
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType

class IosRunTask(
    override val taskName: TaskName,
    override val platform: Platform,
    override val buildType: BuildType,
    override val module: AmperModule,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
    private val runSettings: MobileRunSettings,
) : RunTask {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult = context(
        dependenciesResult.xcodeEnvironment(),
    ) {
        val preparedRun = dependenciesResult.requireSingleDependency<IosPrepareDeviceForRunTask.Result>()
        val waitForDebugger = runSettings.waitForDebugger
        terminal.println(terminal.theme.success(
            if (waitForDebugger) "Starting the app, it will wait for a debugger to attach" else "Starting the app"
        ))
        val pid = if (platform.isIosSimulator) {
            processRunner.launchAppOnDevice(preparedRun.readyDeviceId, preparedRun.bundleId, waitForDebugger)
        } else {
            processRunner.launchAppOnPhysicalDevice(preparedRun.readyDeviceId, preparedRun.bundleId, startStopped = waitForDebugger)
        }
        // TODO: Provide the output for tooling in a more robust way
        terminal.println(
            "Launch info: deviceId=${preparedRun.readyDeviceId.value} bundleId=${preparedRun.bundleId} " +
                    "pid=${pid ?: "unknown"} suspended=$waitForDebugger"
        )
        // At the moment this behavior is unique to the debugging lifecycle
        // (as IDE needs the parent process to stay alive until the debug session ends).
        // However, there are plans to unify the `run` behavior to keep the application lifetime as a child of
        // the Kotlin Toolchain command lifetime to display logs: KTC-4445
        if (waitForDebugger) {
            if (pid == null) {
                terminal.println(terminal.theme.warning("Could not determine the app PID, not waiting for the debug session to end"))
            } else {
                terminal.println("Waiting for the app to exit (press Ctrl+C to stop waiting)")
                if (platform.isIosSimulator) {
                    waitForSimulatorAppExit(pid)
                } else {
                    processRunner.waitForPhysicalDeviceAppExit(preparedRun.readyDeviceId, pid)
                }
                terminal.println("The app has exited")
            }
        }
        return EmptyTaskResult
    }
}
