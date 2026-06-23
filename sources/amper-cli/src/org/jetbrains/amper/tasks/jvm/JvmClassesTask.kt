/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.tasks.ClasspathElementType
import org.jetbrains.amper.tasks.ClasspathProvider
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path

/**
 * Dummy task only needed as a classpath provider because dev tools require depending on classes instead of jars.
 */
// FIXME this task seems redundant. Anything that can be done using this task could be done using the JvmCompileTask
//   directly. No need for an extra task.
internal class JvmClassesTask(override val taskName: TaskName): Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val compileTaskResults = dependenciesResult.filterIsInstance<JvmCompileTask.Result>()
        require(compileTaskResults.isNotEmpty()) {
            "Call classes task without any compilation dependency"
        }
        
        return Result(compileTaskResults.flatMap { it.runtimeClasspath })
    }

    class Result(override val runtimeClasspath: List<Path>): TaskResult, ClasspathProvider {
        override val classpathElementType: ClasspathElementType
            get() = ClasspathElementType.LocallyCompiledClasses
    }
}
