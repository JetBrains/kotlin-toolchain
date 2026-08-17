/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkPackageRequest
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkProvider
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path

class GetAndroidPlatformFileFromPackageTask(
    private val packageRequest: AndroidSdkPackageRequest,
    private val androidSdkProvider: AndroidSdkProvider,
    override val taskName: TaskName,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result {
        val androidSdkPackage = androidSdkProvider.provision(packageRequest)
        return Result(listOf(androidSdkPackage.location))
    }

    data class Result(
        val outputs: List<Path>
    ) : TaskResult
}
