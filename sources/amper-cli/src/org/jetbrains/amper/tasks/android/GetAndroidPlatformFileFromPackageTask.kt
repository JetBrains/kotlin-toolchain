/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path

class GetAndroidPlatformFileFromPackageTask(
    private val packageName: String,
    private val androidSdkPath: Path,
    private val userCacheRoot: AmperUserCacheRoot,
    override val taskName: TaskName,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result {
        val packagePath = SdkInstallManager(userCacheRoot, androidSdkPath)
            .install(packageName)
            .path
        val localFileSystemPackagePath = packagePath
            .split(";")
            .fold(androidSdkPath) { path, component -> path.resolve(component) }
        return Result(listOf(localFileSystemPackagePath))
    }

    data class Result(
        val outputs: List<Path>
    ) : TaskResult
}
