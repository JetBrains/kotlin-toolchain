/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import nl.adaptivity.xmlutil.core.impl.multiplatform.name
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.extract.extractFileToCacheLocation
import org.jetbrains.amper.engine.PackageTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.executablejar.writeExecutableJar
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.jvm.findEffectiveJvmMainClass
import org.jetbrains.amper.run.ToolingArtifactsDownloader
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div

/**
 * Task that creates an executable jar.
 */
class ExecutableJarTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    val incrementalCache: IncrementalCache,
    val userCacheRoot: AmperUserCacheRoot,
    private val taskOutputRoot: TaskOutputRoot,
) : PackageTask {

    private val artifactsDownloader = ToolingArtifactsDownloader(userCacheRoot, incrementalCache)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        check(module.type.isApplication()) { "Illegal module type for ${ExecutableJarTask::class.name}: ${module.type}" }
        val mainClass = module.fragments.filter { !it.isTest }.findEffectiveJvmMainClass()

        val compiledClasses = dependenciesResult
            .filterIsInstance<JvmCompileTask.Result>()
            .flatMap { it.classesOutputRoots }

        val runtimeDependencies = dependenciesResult
            .filterIsInstance<JvmRuntimeClasspathTask.Result>()
            .flatMap { it.jvmRuntimeClasspath }

        val springBootLoaderJar = artifactsDownloader.downloadSpringBootLoader()
        val springBootLoaderJarUnpacked = extractFileToCacheLocation(springBootLoaderJar, userCacheRoot)

        val outputJarPath = taskOutputRoot.path / "${module.userReadableName}-jvm-executable.jar"

        incrementalCache.executeForFiles(
            key = taskName.id.value,
            inputValues = mapOf("mainClass" to mainClass.toString()),
            inputFiles = compiledClasses + runtimeDependencies,
        ) {
            outputJarPath.createParentDirectories().writeExecutableJar(
                compiledClasses,
                runtimeDependencies,
                springBootLoaderJarUnpacked,
                mainClass
            )
            [outputJarPath]
        }
        return Result(outputJarPath)
    }

    override val platform: Platform
        get() = Platform.JVM

    override val buildType: BuildType
        get() = BuildType.Release

    override val format: PackageTask.Format
        get() = PackageTask.Format.ExecutableJar

    class Result(val jarPath: Path) : TaskResult
}
