/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.serialization.paths.SerializablePath
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactType
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

@Serializable
data class LocalPackageInputs(
    // Dump invalidation depends on the sources
    val sources: List<SerializablePath>,
    // also compiler parameters in the manifests
    val manifests: List<SerializablePath>,
)

data class LocalPackageInputsFile(
    override val path: Path,
    val module: AmperModule,
) : Artifact {
    @OptIn(ExperimentalSerializationApi::class)
    val localPackageInputs: LocalPackageInputs? by lazy {
        if (!path.exists()) return@lazy null
        path.inputStream().use {
            swiftPMJson.decodeFromStream(it)
        }
    }
}

fun localPackageInputs(module: AmperModule) = ArtifactSelector(
    type = ArtifactType(LocalPackageInputsFile::class),
    predicate = { it.module == module },
    description = "Local package inputs",
    quantifier = Quantifier.Single,
)

class ComputeLocalPackageInputsTask(
    val module: AmperModule,
    taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
) : ArtifactTaskBase() {
    private val output = taskOutputRoot.path.resolve("localPackageInputs.json")

    val swiftPMDependenciesArtifact by swiftPMDependenciesArtifact(module)

    val localPackageInputsFile by LocalPackageInputsFile(output, module)

    @Serializable
    data class PackageDescription(
        val targets: List<PackageTarget>
    ) {
        @Serializable
        data class PackageTarget(
            val path: String,
            val type: String,
            @kotlinx.serialization.SerialName("module_type") val moduleType: String,
        )
    }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val localPackageDependencies = swiftPMDependenciesArtifact.swiftPMDependencies.localPackageDependencies
        if (localPackageDependencies.isEmpty()) {
            localPackageInputsFile.path.deleteRecursively()
            return EmptyTaskResult
        }

        val inputManifests = localPackageDependencies.map {
            it.absolutePath.resolve("Package.swift")
        }
        incrementalCache.executeForFiles(
            key = "compute-local-package-inputs-${module.userReadableName}",
            inputValues = emptyMap(),
            inputFiles = inputManifests,
        ) {
            spanBuilder("compute local package inputs").setAmperModule(module).use {
                // FIXME: This doesn't work for transitive local packages
                val localPackageSources = localPackageDependencies.flatMap {
                    findLocalPackageSources(it.absolutePath)
                }
                val output = produces.single().path
                output.parent.createDirectories()

                output.outputStream().use {
                    @OptIn(ExperimentalSerializationApi::class)
                    swiftPMJson.encodeToStream(
                        LocalPackageInputs(
                            sources = localPackageSources,
                            manifests = inputManifests
                        ),
                        it,
                    )
                }

                listOf(output)
            }
        }
        return EmptyTaskResult
    }

    private suspend fun findLocalPackageSources(path: Path): List<Path> {
        val result = runProcess(
            workingDir = path,
            command = ["swift", "package", "describe", "--type", "json"],
            configureEnvironment = { env ->
                env.keys.filter {
                    // Swift CLIs try to compile the manifest for iphonesimulator... with these envs
                    it.startsWith("SDK")
                }.forEach {
                    env.remove(it)
                }
            },
            outputMode = ProcessOutputMode.listenAndCapture(
                LoggingProcessOutputListener(
                    logger,
                    "local package sources discovery: ",
                    stdoutLoggingLevel = Level.DEBUG,
                    stderrLoggingLevel = Level.DEBUG,
                )
            )
        )

        if (result.exitCode != 0) userReadableError("Failed to discover local package sources")

        val packageDescription = swiftPMJson.decodeFromString<PackageDescription>(result.stdout)

        return packageDescription.targets.filter {
            (it.moduleType == "SwiftTarget" || it.moduleType == "ClangTarget") && it.type != "test"
        }.map {
            path.resolve(it.path)
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}