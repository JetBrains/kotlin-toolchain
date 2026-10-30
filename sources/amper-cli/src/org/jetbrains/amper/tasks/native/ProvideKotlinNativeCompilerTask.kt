/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.jetbrains.amper.compilation.KotlinNativeCompiler
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.downloader.Downloader
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.engine.requireSingleDependency
import org.jetbrains.amper.frontend.schema.KotlinVersion
import org.jetbrains.amper.kotlin.native.downloadAndExtractKotlinNative
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.jvm.DefaultJdkTaskResult

internal class ProvideKotlinNativeCompilerTask(
    private val kotlinVersion: KotlinVersion,
    private val userCacheRoot: AmperUserCacheRoot,
) : Task {
    override val taskName: TaskName = kotlinNativeCompilerProvisionTaskName(kotlinVersion)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val jdk = dependenciesResult.requireSingleDependency<DefaultJdkTaskResult>().jdk
        // TODO: Should we use KONAN_DATA_DIR here instead of Amper user cache folder? See AMPER-5319.
        val konanDistribution = context(executionContext.eventSink) {
            Downloader.downloadAndExtractKotlinNative(kotlinVersion.notation, userCacheRoot)
                ?: error("kotlin native compiler is not available for the current platform")
        }
        return Result(
            kotlinVersion = kotlinVersion,
            compiler = KotlinNativeCompiler(konanDistribution, jdk),
        )
    }

    class Result(
        val kotlinVersion: KotlinVersion,
        val compiler: KotlinNativeCompiler,
    ) : TaskResult
}

internal fun List<TaskResult>.requireKotlinNativeCompiler(kotlinVersion: KotlinVersion): KotlinNativeCompiler =
    filterIsInstance<ProvideKotlinNativeCompilerTask.Result>()
        .singleOrNull { it.kotlinVersion == kotlinVersion }
        ?.compiler
        ?: error("Kotlin/Native compiler task result for version ${kotlinVersion.notation} is missing")

internal fun kotlinNativeCompilerProvisionTaskName(kotlinVersion: KotlinVersion): TaskName = TaskName(
    internalName = "provideKotlinNativeCompiler${kotlinVersion.notation}",
    operationMoniker = "provisioning Kotlin/Native compiler ${kotlinVersion.notation}",
)
