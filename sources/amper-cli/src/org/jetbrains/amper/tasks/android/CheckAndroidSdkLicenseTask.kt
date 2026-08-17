/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkLicenseCheckResult
import org.jetbrains.amper.android.sdk.provisioning.AndroidSdkProvider
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.tasks.TaskResult

class CheckAndroidSdkLicenseTask(
    private val androidSdkProvider: AndroidSdkProvider,
    override val taskName: TaskName,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val licenseResult = context(executionContext) {
            androidSdkProvider.checkLicensesAndReport()
        }
        when (licenseResult) {
            AndroidSdkLicenseCheckResult.Accepted -> {}
            is AndroidSdkLicenseCheckResult.Unaccepted -> userReadableError("Android SDK licenses should be accepted to proceed. See the message above.")
        }
        return Result()
    }

    class Result : TaskResult
}
