/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.core.extract.extractFileToLocation
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.ClasspathElementType
import org.jetbrains.amper.tasks.ClasspathProvider
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.TaskResult
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

internal class TransformAarExternalDependenciesTask(
    override val taskName: TaskName,
    private val incrementalCache: IncrementalCache,
) : Task {
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val resolvedAndroidDependencies = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .map { it.compileClasspath + it.runtimeClasspath }
            .flatten()

        val executionResult = incrementalCache.execute(
            key = taskName.id.value,
            inputValues = emptyMap(),
            inputFiles = resolvedAndroidDependencies,
        ) {
            if (resolvedAndroidDependencies.isNotEmpty()) {
                logger.info("Transforming AAR external dependencies...")
            }
            val outputs = resolvedAndroidDependencies.extractAars().flatMap(::extractedAarClasspathJars)
            IncrementalCache.ExecutionResult(outputs, emptyMap())
        }
        return Result(executionResult.outputFiles, executionResult.outputFiles)
    }

    internal class Result(
        override val compileClasspath: List<Path>,
        override val runtimeClasspath: List<Path>,
    ) : TaskResult, ClasspathProvider {
        override val classpathElementType: ClasspathElementType = ClasspathElementType.TransformedDependencies
    }
}

internal fun extractedAarClasspathJars(extractedAar: Path): List<Path> = buildList {
    val classesJar = extractedAar / "classes.jar"
    if (classesJar.isRegularFile()) {
        add(classesJar)
    }

    val libsDirectory = extractedAar / "libs"
    if (libsDirectory.isDirectory()) {
        addAll(
            libsDirectory.listDirectoryEntries()
                .filter { it.isRegularFile() && it.extension == "jar" }
                .sortedBy { it.fileName.toString() },
        )
    }
}

private suspend fun List<Path>.extractAars(): List<Path> = coroutineScope {
    filter { it.extension == "aar" }
        .map {
            async {
                val targetFolder = it.parent / it.nameWithoutExtension
                extractFileToLocation(it, targetFolder)
                targetFolder
            }
        }.awaitAll()
}
