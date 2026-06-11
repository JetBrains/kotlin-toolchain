/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
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
        val classpath = buildRuntimeClasspath(dependenciesResult)

        return Result(
            jvmRuntimeClasspath = classpath,
            module = module,
            isTest = isTest,
        )
    }

    // TODO while this way of building the classpath is deterministic and consistent, the resulting order is decided by
    //  the task dependency order. This is not a problem per se, but it means that Kotlin Toolchain developers might
    //  inadvertently change the order by re-ordering task dependencies. If we want to define a more semantic order
    //  between different bits of classpaths, we could add some sort of 'type' or 'order' property to
    //  `ClasspathProvider`, and order the parts based on this property here.
    private fun buildRuntimeClasspath(dependenciesResult: List<TaskResult>): List<Path> {
        val classpathElements = dependenciesResult.filterIsInstance<ClasspathProvider>()
        check(classpathElements.isNotEmpty()) {
            "No ${ClasspathProvider::class.simpleName} results are found in dependencies"
        }
        return classpathElements.flatMap { it.runtimeClasspath }.distinct()
    }

    class Result(
        val jvmRuntimeClasspath: List<Path>,
        val module: AmperModule,
        val isTest: Boolean,
    ): TaskResult
}
