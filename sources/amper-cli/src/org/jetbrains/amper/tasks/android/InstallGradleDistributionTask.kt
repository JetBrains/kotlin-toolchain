/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.gradle.util.GradleVersion
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path

/**
 * Overrides the Gradle distribution URL for Android builds.
 * Point it at a mirror (e.g. https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-{{version}}-bin.zip)
 * when services.gradle.org is unreachable. When unset, Gradle's default distribution URL is used.
 *
 * Use the `{{version}}` placeholder to define where to insert the required Gradle version.
 */
private const val GRADLE_DISTRIBUTION_URL_ENV = "KOTLIN_TOOLCHAIN_GRADLE_DISTRIBUTION_URL"

private const val DEFAULT_GRADLE_DISTRIBUTION_URL =
    "https://services.gradle.org/distributions/gradle-{{version}}-bin.zip"

/**
 * Provisions the Gradle distribution used by the delegated Android builds.
 *
 * Downloading it ourselves (instead of letting the Tooling API do it) reports the progress properly,
 * while the Tooling API prints raw progress dots right into the process' standard output.
 */
internal class InstallGradleDistributionTask(
    private val userCacheRoot: AmperUserCacheRoot,
) : Task {

    override val taskName: TaskName = AndroidGlobalTaskType.InstallGradleDistribution

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val currentGradleVersion = GradleVersion.current().version
        val distributionUrl = (System.getenv(GRADLE_DISTRIBUTION_URL_ENV)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_GRADLE_DISTRIBUTION_URL)
            .replace("{{version}}", currentGradleVersion)

        val gradleHome = context(executionContext.eventSink) {
            val archive = operationEventScope("downloading distribution $currentGradleVersion") {
                Downloader.downloadFileToCacheLocation(distributionUrl, userCacheRoot)
            }
            operationEventScope("extracting distribution $currentGradleVersion") {
                extractFileToCacheLocation(archive, userCacheRoot, ExtractOptions.STRIP_ROOT)
            }
        }
        return Result(gradleHome)
    }

    class Result(val gradleHome: Path) : TaskResult
}
