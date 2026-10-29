/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.wasm

import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.KotlinCompilationType
import org.jetbrains.amper.compilation.KotlinUserSettings
import org.jetbrains.amper.compilation.ResolvedCompilerPlugin
import org.jetbrains.amper.compilation.kotlinWasmCompilerArgs
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.SourceRoot
import org.jetbrains.amper.tasks.web.WebLinkTask
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path

internal class WasmWasiLinkTask(
    module: AmperModule,
    platform: Platform,
    userCacheRoot: AmperUserCacheRoot,
    buildOutputRoot: AmperBuildOutputRoot,
    incrementalCache: IncrementalCache,
    taskName: TaskName,
    tempRoot: AmperProjectTempRoot,
    isTest: Boolean,
    compileKLibTaskId: TaskId,
    processRunner: ProcessRunner,
    buildType: BuildType,
    kotlinArtifactsDownloader: KotlinArtifactsDownloader =
        KotlinArtifactsDownloader(userCacheRoot, incrementalCache),
) : WebLinkTask(
    module,
    platform,
    userCacheRoot,
    buildOutputRoot,
    incrementalCache,
    taskName,
    tempRoot,
    isTest,
    buildType,
    processRunner,
    compileKLibTaskId,
    kotlinArtifactsDownloader,
) {
    override val expectedPlatform: Platform
        get() = Platform.WASM_WASI

    override fun kotlinCompilerArgs(
        kotlinUserSettings: KotlinUserSettings,
        compilerPlugins: List<ResolvedCompilerPlugin>,
        libraryPaths: List<Path>,
        outputPath: Path,
        friendPaths: List<Path>,
        fragments: List<Fragment>,
        sourceFiles: List<Path>,
        additionalSourceRoots: List<SourceRoot>,
        moduleName: String,
        compilationType: KotlinCompilationType,
        buildType: BuildType,
        include: Path?,
        cacheDirectory: Path?,
    ): List<String> =
        kotlinWasmCompilerArgs(
            WasmTarget.WASI,
            kotlinUserSettings,
            compilerPlugins,
            libraryPaths,
            outputPath,
            friendPaths,
            fragments,
            sourceFiles,
            additionalSourceRoots,
            moduleName,
            compilationType,
            buildType,
            include,
        )
}
