/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.FreestandingJdkProvisioning
import org.jetbrains.amper.jvm.getDefaultJdk

internal class ProvideKotlinCompilerRuntimeTask(private val jdkProvider: JdkProvider) : Task {
    override val taskName = TASK_NAME

    @OptIn(FreestandingJdkProvisioning::class)
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result = Result(
        context(executionContext.problemReporter, executionContext.eventSink) {
            jdkProvider.getDefaultJdk()
        }
    )

    class Result(val jdk: Jdk) : TaskResult

    companion object {
        val TASK_NAME = TaskName(
            internalName = "provideKotlinCompilerRuntime",
            operationMoniker = "provisioning runtime for Kotlin compiler",
        )
    }
}