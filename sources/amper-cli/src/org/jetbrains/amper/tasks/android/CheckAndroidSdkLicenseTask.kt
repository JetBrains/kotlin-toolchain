/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.jetbrains.amper.android.sdk.provisioning.SdkInstallManager
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path
import kotlin.io.path.div

class CheckAndroidSdkLicenseTask(
    private val androidSdkPath: Path,
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
): Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val unacceptedLicenseIds = SdkInstallManager(userCacheRoot, androidSdkPath)
            .findUnacceptedSdkLicenseIds(incrementalCache)
        if (unacceptedLicenseIds.isNotEmpty()) {
            val licensesListText = unacceptedLicenseIds.joinToString("\n") { " - $it" }
            val licensesCommand = "${androidSdkPath / "cmdline-tools" / "22.0" / "bin" / "sdkmanager"} --licenses"
            userReadableError("Some licenses have not been accepted in the Android SDK:\n" +
                    "$licensesListText\n" +
                    "Run \"$licensesCommand\" to review and accept them")
        }
        return Result()
    }

    class Result : TaskResult
}
