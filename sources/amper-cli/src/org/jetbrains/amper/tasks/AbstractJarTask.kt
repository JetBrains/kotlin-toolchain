/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import kotlinx.serialization.json.Json
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jar.JarConfig
import org.jetbrains.amper.jar.ZipInput
import org.jetbrains.amper.jar.writeJar
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.pathString

abstract class AbstractJarTask(
    override val taskName: TaskName,
    private val incrementalCache: IncrementalCache,
) : ArtifactTaskBase() {

    context(_: ProblemReporter)
    protected abstract suspend fun assembleInputDirs(dependenciesResult: List<TaskResult>): List<ZipInput>
    protected abstract fun outputJarPath(): Path
    protected abstract fun jarConfig(): JarConfig

    protected abstract fun createResult(jarPath: Path): Result

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val inputDirs = assembleInputDirs(dependenciesResult)
        val outputJarPath = outputJarPath()

        val jarConfig = jarConfig()

        incrementalCache.execute(
            key = taskName.id.value,
            inputValues = mapOf(
                "jarConfig" to Json.encodeToString(jarConfig),
                "inputDirsDestPaths" to inputDirs.map { it.destPathInArchive }.toString(),
                "outputJarPath" to outputJarPath.pathString,
            ),
            inputFiles = inputDirs.map { it.path }
        ) {
            outputJarPath.createParentDirectories().writeJar(inputDirs, jarConfig)
            IncrementalCache.ExecutionResult(outputFiles = listOf(outputJarPath))
        }
        return createResult(outputJarPath)
    }

    abstract class Result(
        val jarPath: Path,
    ) : TaskResult
}
