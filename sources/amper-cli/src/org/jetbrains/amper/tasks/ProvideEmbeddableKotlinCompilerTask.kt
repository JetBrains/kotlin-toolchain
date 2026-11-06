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
import org.jetbrains.amper.frontend.schema.KotlinVersion
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.Jdk
import java.nio.file.Path

internal class ProvideEmbeddableKotlinCompilerTask(
    private val version: KotlinVersion,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache,
) : Task {
    override val taskName = kotlinCompilerEmbeddableProvisionTaskName(version)
    private val downloader = KotlinArtifactsDownloader(userCacheRoot, incrementalCache)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result =
        Result(version, downloader.downloadKotlinCompilerEmbeddable(version))

    class Result(private val version: KotlinVersion, val compilerJars: List<Path>) : TaskResult {
        fun compiler(jdk: Jdk) = KotlinCompiler(compilerJars, version, jdk)
    }
}

internal fun kotlinCompilerEmbeddableProvisionTaskName(version: KotlinVersion): TaskName = TaskName(
    internalName = "provideKotlinCompilerEmbeddable${version.notation}",
    operationMoniker = "provisioning Kotlin compiler ${version.notation}",
)