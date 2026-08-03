/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectRoot
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.RunTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.jdkSettings
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.getEffectiveJvmMainClass
import org.jetbrains.amper.jvm.getJdkOrUserError
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.processes.PrintToTerminalProcessOutputListener
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runJava
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.JvmMainRunSettings
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path

abstract class AbstractJvmRunTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    protected val userCacheRoot: AmperUserCacheRoot,
    protected val projectRoot: AmperProjectRoot,
    protected val tempRoot: AmperProjectTempRoot,
    protected val terminal: Terminal,
    protected val runSettings: JvmMainRunSettings,
    protected val incrementalCache: IncrementalCache?,
    protected val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
) : RunTask {
    override val platform = Platform.JVM
    protected val fragments = module.fragments.filter { !it.isTest && it.platforms.contains(Platform.JVM) }

    protected open fun getEnvironment(dependenciesResult: List<TaskResult>): Map<String, String> = emptyMap()

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val result = processRunner.runJava(
            jdk = getJdk(),
            workingDir = runSettings.workingDir,
            mainClass = getMainClass(dependenciesResult),
            classpath = getClasspath(dependenciesResult),
            programArgs = runSettings.programArgs,
            argsMode = ArgsMode.ArgFile(tempRoot = tempRoot),
            jvmArgs = getJvmArgs(dependenciesResult),
            environment = getEnvironment(dependenciesResult),
            // TODO change to ProcessOutputMode.Inherit to fix KTC-5596
            outputMode = ProcessOutputMode.listen(PrintToTerminalProcessOutputListener(terminal)),
            input = ProcessInput.Inherit,
        )

        if (result.exitCode != 0) {
            userReadableError("Process failed with exit code ${result.exitCode}", exitCode = result.exitCode)
        }

        return EmptyTaskResult
    }

    context(_: ProblemReporter)
    protected open suspend fun getJdk(): Jdk = jdkProvider.getJdkOrUserError(module.jdkSettings)

    context(_: ProblemReporter)
    protected open suspend fun getJvmArgs(dependenciesResult: List<TaskResult>): List<String> = buildList {
        if (fragments.any { it.settings.ktor.enabled }) {
            add("-Dio.ktor.development=true")
        }
        add("-ea")
        addAll(runSettings.userJvmArgs)
    }

    context(_: ProblemReporter)
    protected open suspend fun getClasspath(dependenciesResult: List<TaskResult>): List<Path> {
        val runtimeClasspathTask = dependenciesResult.filterIsInstance<JvmRuntimeClasspathTask.Result>().singleOrNull()
            ?: error("Could not find a single ${JvmRuntimeClasspathTask.Result::class.simpleName} in dependencies of ${taskName.id.value}")
        return runtimeClasspathTask.jvmRuntimeClasspath
    }

    protected open suspend fun getMainClass(dependenciesResult: List<TaskResult>): String =
        runSettings.userJvmMainClass ?: fragments.getEffectiveJvmMainClass()
}
