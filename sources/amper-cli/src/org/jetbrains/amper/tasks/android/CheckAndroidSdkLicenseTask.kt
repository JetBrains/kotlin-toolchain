/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

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
    private val acceptedLicenseIds: Set<String>,
    override val taskName: TaskName,
): Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val installManager = SdkInstallManager(userCacheRoot, androidSdkPath)
        val unacceptedLicenseIds = installManager.findUnacceptedSdkLicenseIds(incrementalCache)
        if (unacceptedLicenseIds.isNotEmpty()) {
            // Licenses the user explicitly listed in settings.android.acceptedLicenses
            // are accepted here (hash files written), like sdkmanager --licenses would.
            val acceptedByUser = acceptedLicenseIds.intersect(unacceptedLicenseIds.toSet())
            if (acceptedByUser.isNotEmpty()) {
                installManager.acceptSdkLicenses(acceptedByUser, incrementalCache)
            }
            val remaining = installManager.findUnacceptedSdkLicenseIds(incrementalCache)
            if (remaining.isNotEmpty()) {
                val licensesListText = remaining.joinToString("\n") { " - $it" }
                val licensesCommand = "${androidSdkPath / "cmdline-tools" / "latest" / "bin" / "sdkmanager"} --licenses"
                userReadableError("Some licenses have not been accepted in the Android SDK:\n" +
                        "$licensesListText\n" +
                        "Run \"$licensesCommand\" to review and accept them, or list the licenses you " +
                        "explicitly accept in module.yaml under settings.android.acceptedLicenses " +
                        "(e.g. [android-sdk-license])")
            }
        }
        return Result()
    }

    class Result : TaskResult
}
