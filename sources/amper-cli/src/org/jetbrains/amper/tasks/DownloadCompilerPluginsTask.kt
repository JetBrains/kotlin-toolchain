/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.ResolvedCompilerPlugin
import org.jetbrains.amper.compilation.compilerPluginConfigs
import org.jetbrains.amper.concurrency.mapConcurrently
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.incrementalcache.IncrementalCache

internal class DownloadCompilerPluginsTask(
    override val taskName: TaskName,
    private val fragment: Fragment,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache,
) : Task {
    private val downloader = KotlinArtifactsDownloader(userCacheRoot, incrementalCache)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result = Result(
        plugins = fragment.compilerPluginConfigs().mapConcurrently { config ->
            ResolvedCompilerPlugin(
                config = config,
                classpath = downloader.downloadKotlinCompilerPlugin(
                    pluginConfig = config,
                    repositories = fragment.module.mavenResolveRepositories.map { it.toRepository() }),
            )
        },
    )

    class Result(val plugins: List<ResolvedCompilerPlugin>) : TaskResult
}
