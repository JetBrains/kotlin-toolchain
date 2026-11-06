/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.compiler.KotlinCompiler
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.schema.KotlinVersion
import org.jetbrains.amper.incrementalcache.IncrementalCache

internal class ProvideEmbeddableKotlinCompilerTask(
    private val version: KotlinVersion,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache,
) : Task {
    override val taskName = kotlinCompilerEmbeddableProvisionTaskName(version)
    private val downloader = KotlinArtifactsDownloader(userCacheRoot, incrementalCache)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result {
        val jdk = dependenciesResult.requireSingleDependency<ProvideKotlinCompilerRuntimeTask.Result>().jdk
        val compilerJars = downloader.downloadKotlinCompilerEmbeddable(version)
        return Result(KotlinCompiler(compilerJars, version, jdk))
    }

    class Result(val compiler: KotlinCompiler) : TaskResult
}

internal fun kotlinCompilerEmbeddableProvisionTaskName(version: KotlinVersion): TaskName = TaskName(
    internalName = "provideKotlinCompilerEmbeddable${version.notation}",
    operationMoniker = "provisioning Kotlin compiler ${version.notation}",
)