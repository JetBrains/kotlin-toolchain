/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.IpaPackageSettings
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.pathString

internal class IosArchiveTask(
    private val module: AmperModule,
    private val platform: Platform,
    private val buildType: BuildType,
    override val taskName: TaskName,
    private val taskOutputPath: TaskOutputRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
    private val buildSettingsResolution: XcodeBuildSettingsResolution,
    private val settings: IpaPackageSettings,
) : ArtifactTaskBase() {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult = context(
        executionContext.eventSink,
        dependenciesResult.xcodeEnvironment(),
    ) {
        val prebuildResult = dependenciesResult.requireSingleDependency<IosPreBuildTask.Result>()
        resolveSigning(
            buildSettingsResolution.getResolver(buildType, dependenciesResult),
            module.userReadableName,
            buildType,
            settings,
        )

        val workingDir = taskOutputPath.path.createDirectories()
        val archivePath = (workingDir / "${module.userReadableName}.xcarchive").also(Path::deleteRecursively)
        val command = buildList {
            addAll(xcodebuildCommonAppArgs(module, platform, buildType, XcodebuildDirs(workingDir)))
            this += "-archivePath"; this += archivePath.pathString
            addAll(provisioningArgs(settings.authKey))
            this += "archive"
        }
        runXcodebuildChecked(
            module = module,
            command = command,
            workingDir = workingDir,
            prebuildResult = prebuildResult,
            userCacheRoot = userCacheRoot,
            terminal = terminal,
            processRunner = processRunner,
            step = "xcodebuild archive",
        )

        Result(archivePath = archivePath, prebuildResult = prebuildResult)
    }

    class Result(
        val archivePath: Path,
        val prebuildResult: IosPreBuildTask.Result,
    ) : TaskResult
}
