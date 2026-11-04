/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.bta.loadMaybeCachedImpl
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.schema.KotlinVersion
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains

@OptIn(ExperimentalBuildToolsApi::class)
internal class ProvideKotlinToolchainsTask(
    private val kotlinVersion: KotlinVersion,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache,
) : Task {
    override val taskName: TaskName = kotlinToolchainsProvisionTaskName(kotlinVersion)
    private val downloader = KotlinArtifactsDownloader(userCacheRoot, incrementalCache)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result = Result(
        toolchains = KotlinToolchains.loadMaybeCachedImpl(kotlinVersion, downloader),
        // Toolchains instance is fully defined by its version.
        identity = kotlinVersion.notation,
    )

    class Result(
        val toolchains: KotlinToolchains,
        /**
         * Identifies the [toolchains] instance in terms of execution avoidance:
         * [identity] changes <=> [toolchains] changes.
         */
        val identity: String,
    ) : TaskResult
}

internal fun kotlinToolchainsProvisionTaskName(kotlinVersion: KotlinVersion): TaskName = TaskName(
    internalName = "provideKotlinToolchains${kotlinVersion.notation}",
    operationMoniker = "provisioning Kotlin JVM compiler ${kotlinVersion.notation}",
)