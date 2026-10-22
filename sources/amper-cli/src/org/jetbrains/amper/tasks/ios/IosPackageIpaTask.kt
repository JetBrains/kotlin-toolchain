/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.success
import com.jetbrains.cidr.xcode.plist.XMLPlistDriver
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.PackageTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.IpaPackageSettings
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString

/**
 * Packages an iOS app into a signed `.ipa` using a shared archive and `xcodebuild -exportArchive`.
 *
 * The archive is produced by [IosArchiveTask], which is also used by the TestFlight upload task.
 */
internal class IosPackageIpaTask(
    override val module: AmperModule,
    override val platform: Platform,
    override val buildType: BuildType,
    override val taskName: TaskName,
    private val taskOutputPath: TaskOutputRoot,
    private val buildOutputRoot: AmperBuildOutputRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
    private val buildSettingsResolution: XcodeBuildSettingsResolution,
    private val settings: IpaPackageSettings,
) : PackageTask {
    init {
        require(platform == Platform.IOS_ARM64) { "Only iOS device platform can be packaged into ipa, got: $platform" }
    }

    override val format: PackageTask.Format
        get() = PackageTask.Format.Ipa

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult = context(
        executionContext.eventSink,
        dependenciesResult.xcodeEnvironment(),
    ) {
        val archiveResult = dependenciesResult.requireSingleDependency<IosArchiveTask.Result>()
        val prebuildResult = archiveResult.prebuildResult
        val teamId = resolveSigning(
            buildSettingsResolution.getResolver(buildType, dependenciesResult),
            module.userReadableName,
            buildType,
            settings,
        )

        val workingDir = taskOutputPath.path.createDirectories()
        val exportPath = (workingDir / "export").also(Path::deleteRecursively)

        val exportOptionsPath = settings.exportOptionsPlistPath ?: (workingDir / "ExportOptions.plist")
        if (settings.exportOptionsPlistPath == null) {
            writeExportOptions(exportOptionsPath, teamId)
        }
        operationEventScope("exporting the archive") {
            export(archiveResult.archivePath, exportPath, exportOptionsPath, workingDir, prebuildResult)
        }

        val exportedIpa = exportPath.listDirectoryEntries().singleOrNull { it.extension == "ipa" }
            ?: userReadableError("xcodebuild -exportArchive didn't produce an .ipa file in $exportPath")
        val ipaPath = (buildOutputRoot.path / "packages" / format.value).createDirectories() /
                "${module.userReadableName}-${buildType.value}.ipa"
        exportedIpa.copyTo(ipaPath, StandardCopyOption.REPLACE_EXISTING)
        terminal.success("IPA is available at $ipaPath")
        return Result(ipaPath = ipaPath)
    }

    context(executionContext: TaskGraphExecutionContext, _: XcodeEnvironment)
    private suspend fun export(
        archivePath: Path,
        exportPath: Path,
        exportOptionsPath: Path,
        workingDir: Path,
        prebuildResult: IosPreBuildTask.Result,
    ) {
        val command = buildList {
            this += XCRUN_EXECUTABLE
            this += "xcodebuild"
            this += "-exportArchive"
            this += "-archivePath"; this += archivePath.pathString
            this += "-exportPath"; this += exportPath.pathString
            this += "-exportOptionsPlist"; this += exportOptionsPath.pathString
            addAll(provisioningArgs(settings.authKey))
        }
        runXcodebuildChecked(
            module = module,
            command = command,
            workingDir = workingDir,
            prebuildResult = prebuildResult,
            userCacheRoot = userCacheRoot,
            terminal = terminal,
            processRunner = processRunner,
            step = "xcodebuild -exportArchive",
        )
    }

    private fun writeExportOptions(path: Path, teamId: String) {
        val options = mapOf(
            "method" to "debugging",
            "destination" to "export",
            "teamID" to teamId,
            "signingStyle" to "automatic",
        )
        XMLPlistDriver().write(options.toPlist(), path.toFile())
    }

    class Result(
        /**
         * The path to the exported `.ipa` in the conventional, deterministic location.
         */
        val ipaPath: Path,
    ) : TaskResult
}

internal fun resolveSigning(
    resolver: XcodeBuildSettingsResolution.Resolver,
    moduleName: String,
    buildType: BuildType,
    settings: IpaPackageSettings,
): String {
    if (resolver.codeSignStyle.equals("Manual", ignoreCase = true) && settings.exportOptionsPlistPath == null) {
        userReadableError(
            "Cannot archive the iOS app for module '$moduleName': the Xcode project uses manual teamId " +
                    "(`CODE_SIGN_STYLE=Manual`) for the ${buildType.name} configuration. " +
                    "Provide export options with --ipa-export-options-plist-path."
        )
    }
    val teamId = resolver.developmentTeam?.takeIf { it.isNotBlank() } ?: userReadableError(
        "Cannot archive the iOS app for module '$moduleName': the `DEVELOPMENT_TEAM` build setting " +
                "is not set for the ${buildType.name} configuration in the Xcode project. " +
                "Select a development team in the Xcode project editor (Signing & Capabilities) and try again."
    )
    if (resolver.codeSigningAllowed == "NO") {
        userReadableError(
            "Cannot archive the iOS app for module '$moduleName': code signing is disabled " +
                    "(`CODE_SIGNING_ALLOWED=NO`) for the ${buildType.name} configuration in the Xcode project."
        )
    }
    return teamId
}
