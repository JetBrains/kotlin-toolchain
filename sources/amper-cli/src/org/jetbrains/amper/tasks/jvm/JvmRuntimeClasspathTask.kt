/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.ClasspathElementType
import org.jetbrains.amper.tasks.ClasspathProvider
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path

class JvmRuntimeClasspathTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val isTest: Boolean,
): Task {

context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val classpathElements = dependenciesResult.filterIsInstance<ClasspathProvider>()
        check(classpathElements.isNotEmpty()) {
            "No ${ClasspathProvider::class.simpleName} results are found in task dependencies"
        }
        check(classpathElements.count { it.classpathElementType == ClasspathElementType.ExternalMavenDependencies } <= 1) {
            "Only one classpath element bringing external Maven dependencies is allowed. All external dependencies " +
                    "should be resolved together so that conflict resolution can do its job properly."
        }
        val classpath = classpathElements
            .sortedBy { it.classpathElementType.order }
            .flatMap { it.runtimeClasspath }
            .distinct()

        return Result(
            jvmRuntimeClasspath = classpath,
            module = module,
            isTest = isTest,
        )
    }

    class Result(
        val jvmRuntimeClasspath: List<Path>,
        val module: AmperModule,
        val isTest: Boolean,
    ): TaskResult
}
