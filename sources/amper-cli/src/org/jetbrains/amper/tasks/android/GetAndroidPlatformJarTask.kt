/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.tasks.ClasspathElementType
import org.jetbrains.amper.tasks.ClasspathProvider
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path

class GetAndroidPlatformJarTask(
    private val getAndroidPlatformFileFromPackageTask: GetAndroidPlatformFileFromPackageTask
) : Task {
    override val taskName: TaskName
        get() = getAndroidPlatformFileFromPackageTask.taskName

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val result = getAndroidPlatformFileFromPackageTask.run(dependenciesResult)
        val classpath = result.outputs.map { it.resolve("android.jar") }
        return Result(classpath)
    }

    internal class Result(
        override val compileClasspath: List<Path>,
    ) : TaskResult, ClasspathProvider {
        override val classpathElementType: ClasspathElementType
            get() = ClasspathElementType.PlatformJar
    }
}
