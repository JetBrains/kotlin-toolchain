/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("TasksKt")

package org.jetbrains.amper.engine

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.PackageTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.extension

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
    /**
     * [value] is the name of the format in the CLI, and [fileExtension] is the extension (without the leading
     * dot) of the package files that the format produces.
     *
     * [fileExtension] is used to tell the packages apart from the other artifacts that the underlying build
     * tooling may produce alongside them (e.g. a newer version of the Android Gradle Plugin).
     */
    enum class Format(val value: String, val fileExtension: String) {
        Jar("jar", "jar"),
        ExecutableJar("executable-jar", "jar"),
        Aab("aab", "aab"),
        MavenCentralBundle("maven-central-bundle", "zip"),
        Ipa("ipa", "ipa"),
        // TODO DistZip("dist-zip", "zip"),
    }

    val format: Format
    val module: AmperModule

    /**
     * Ensures at compile time that packaging tasks report the paths of the packages they produce
     * via [PackageTaskResult.packagePaths].
     */
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): PackageTaskResult
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

/**
 * The paths of the packages of the [format] produced by the packaging task that returned this result.
 *
 * The other artifacts that the underlying build tooling may produce alongside the packages are not included.
 */
internal fun TaskResult?.packagePathsOf(format: PackageTask.Format): List<Path> =
    (this as? PackageTaskResult)?.packagePaths.orEmpty().filter { it.extension == format.fileExtension }
