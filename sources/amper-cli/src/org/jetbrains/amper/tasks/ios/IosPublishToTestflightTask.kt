/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.PublishTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.tasks.IosPublishSettings
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.pathString

internal class IosPublishToTestflightTask(
    override val taskName: TaskName,
    private val settings: IosPublishSettings,
    private val taskOutputPath: TaskOutputRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
) : PublishTask {
    override val module get() = settings.module
    override val targetRepositoryId get() = "testflight"
    override val publishesToLocalRepository get() = false
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult = context(
        executionContext.eventSink,
        dependenciesResult.xcodeEnvironment(),
    ) {
        val archive = dependenciesResult.requireSingleDependency<IosArchiveTask.Result>()
        val workingDir = taskOutputPath.path.createDirectories()
        val archiveInfoPlistPath = archive.archivePath / "Info.plist"
        val identifiersBefore = distributionIdentifiers(readXcarchiveDistributions(archiveInfoPlistPath))
        val command = buildList {
            this += XCRUN_EXECUTABLE
            this += "xcodebuild"
            this += "-exportArchive"
            this += "-archivePath"; this += archive.archivePath.pathString
            this += "-exportOptionsPlist"; this += settings.exportOptionsPlistPath.pathString
            addAll(provisioningArgs(settings.authKey))
        }

        runXcodebuildChecked(
            module = module,
            command = command,
            workingDir = workingDir,
            prebuildResult = archive.prebuildResult,
            userCacheRoot = userCacheRoot,
            terminal = terminal,
            processRunner = processRunner,
            step = "uploading the Xcode archive to App Store Connect",
        )
        val uploadId = newUploadId(identifiersBefore, readXcarchiveDistributions(archiveInfoPlistPath))
        Result(uploadId)
    }

    class Result(
        val uploadId: String,
    ) : TaskResult
}
