/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("TasksKt")

package org.jetbrains.amper.engine

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType

interface Task {

    val taskName: TaskName

    context(executionContext: TaskGraphExecutionContext)
    suspend fun run(dependenciesResult: List<TaskResult>): TaskResult
}

interface MaybeBuildTypeAware {
    /**
     * If `null`, then build type dimension is not applicable to this task.
     */
    val buildType: BuildType?
}

interface MaybePlatformAware {
    /**
     * If `null`, the task is platform-independent.
     */
    val platform: Platform?
}

interface PlatformAware : MaybePlatformAware {
    override val platform: Platform
}

interface RunTask : Task, MaybeBuildTypeAware, PlatformAware {
    override val platform: Platform
    val module: AmperModule
}

interface PackageTask : Task, MaybeBuildTypeAware, MaybePlatformAware {
    enum class Format(val value: String) {
        Jar("jar"),
        ExecutableJar("executable-jar"),
        Aab("aab"),
        MavenCentralBundle("maven-central-bundle"),
        // TODO DistZip("dist-zip"),
    }

    val format: Format
    val module: AmperModule
}

interface PublishTask : Task {
    val module: AmperModule
    val targetRepositoryId: String

    /**
     * Whether this task publishes to the local Maven repository of this machine.
     *
     * Such publications may contain machine-specific data, such as absolute paths, while the ones going to
     * repositories that are shared with other machines may not.
     */
    val publishesToLocalRepository: Boolean
}

interface TestTask : Task, MaybeBuildTypeAware, PlatformAware {
    override val platform: Platform
    val module: AmperModule
}

/**
 * A task attached to the 'build' command.
 */
interface BuildTask : Task, MaybeBuildTypeAware, PlatformAware {
    val module: AmperModule
    val isTest: Boolean
    override val platform: Platform
}

/**
 * A task that generates a klib that is required for the IDE to analyze code correctly.
 * Examples:
 *  - `cinterop` commonization
 *  - `cinterop` leaf klib
 *
 * @see org.jetbrains.amper.cli.AmperBackend.generateKlibsForIde
 */
interface GenerateKlibsForIdeTask : Task

/**
 * Find a task dependency with a specified type.
 */
inline fun <reified T : TaskResult> List<TaskResult>.requireSingleDependency() =
    filterIsInstance<T>().firstOrNull() ?: error("Expected to have single \"${T::class.simpleName}\" as a dependency")