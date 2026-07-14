/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jar.JarConfig
import org.jetbrains.amper.jar.ZipInput
import org.jetbrains.amper.jar.writeJar
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.stdlib.io.path.isEmptyDirectory
import org.jetbrains.amper.tasks.MetadataCompileTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists

typealias GradleVariant = org.jetbrains.amper.dependency.resolution.metadata.json.module.Variant
typealias KotlinProjectStructureVariant = org.jetbrains.kotlin.metadata.format.projectStructure.Variant

class AssembleAllMetadataTask (
    override val taskName: TaskName,
    override val module: AmperModule,
    private val moduleDependencies: ModuleDependencies,
    private val userCacheRoot: AmperUserCacheRoot,
    private val tempRoot: AmperProjectTempRoot,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
): ArtifactTaskBase(), BuildTask {

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        check(module.leafPlatforms.size > 1) { "Running the task ${taskName.id} for a single-platform module is not allowed" }

        logger.infoNoConsole("Assembling all metadata artifact for module ${module.userReadableName}")

        taskOutputRoot.path.deleteRecursively()
        taskOutputRoot.path.createDirectories()

        // todo (AB): [AMPER-719] Add a test for the case where cinterop is declared for the fragment,
        //  but there is no actual sources are attached to the fragment except cinterop defs.
        //  (metadata compilation result is empty directory,
        //  but cinterop sourceSet is there and should be mentioned in the module descriptor)

        // todo (AB): [AMPER-719] Wrap into incremental cache

        val metadataCompilations = dependenciesResult.filterIsInstance<MetadataCompileTask.Result>()

        val projectStructureFilePath = generateKotlinProjectDescriptor(module, taskOutputRoot.path, metadataCompilations)

        val allMetadataJarPath = assembleAllMetadataJar(module, taskOutputRoot.path, metadataCompilations, projectStructureFilePath)

        val allMetadataSourcesJarPath = if (module.publishingSettings.publishSources) {
            assembleAllMetadataSourcesJar(module, taskOutputRoot.path, metadataCompilations)
        } else null

        return Result(
            allMetadataJarPath = allMetadataJarPath,
            allMetadataSourcesJarPath = allMetadataSourcesJarPath,
            module = module,
        )
    }

    private fun assembleAllMetadataJar(
        module: AmperModule,
        outputDirectory: Path,
        metadataCompilations: List<MetadataCompileTask.Result>,
        projectStructureFilePath: Path,
    ): Path? {
        // todo (AB): [AMPER-719] Wrap into incremental cache
        val moduleCoordinates = module.publicationCoordinates(Platform.COMMON)
        val artifactId = moduleCoordinates.artifactId
        val version = moduleCoordinates.version
            ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")

        // Creating all-metadata JAR
        // todo (AB): [AMPER-719] Pack commonized cinterop sourceSets as well
        val inputDirs = buildList {
            metadataCompilations.forEach {
                if (!it.metadataOutputRoot.isEmptyDirectory()) {
                    add(ZipInput(path = it.metadataOutputRoot, destPathInArchive = Path(it.fragment.sourceSetName())))
                }
            }

            add(ZipInput(projectStructureFilePath, Path("META-INF") / projectStructureFilePath.fileName))
        }

        if (inputDirs.isNotEmpty()) {
            val allMetadataJarPath = outputDirectory.resolve("$artifactId-metadata-$version.jar")
            allMetadataJarPath.createParentDirectories().writeJar(inputDirs, JarConfig())
            return allMetadataJarPath
        }

        return null
    }

    private fun assembleAllMetadataSourcesJar(
        module: AmperModule,
        outputDirectory: Path,
        metadataCompilations: List<MetadataCompileTask.Result>,
    ): Path? {
        // todo (AB): [AMPER-719] Wrap into incremental cache
        val moduleCoordinates = module.publicationCoordinates(Platform.COMMON)
        val artifactId = moduleCoordinates.artifactId
        val version = moduleCoordinates.version
            ?: error("Missing 'version' in publishing settings of module '${module.userReadableName}'")

        // Creating all-metadata sources JAR
        val inputDirs = buildList {
            metadataCompilations.forEach {
                val fragment = it.fragment
                addAll(
                    fragment.sourceRoots
                    .filter { it.exists() }
                    .map { ZipInput(path = it, destPathInArchive = Path(fragment.sourceSetName())) }
                )
            }
        }

        val allMetadataSourcesJarPath = outputDirectory.resolve("$artifactId-kotlin-$version-sources.jar")
        allMetadataSourcesJarPath.createParentDirectories().writeJar(inputDirs, JarConfig())
        return allMetadataSourcesJarPath
    }

    override val isTest = false
    override val platform = Platform.COMMON
    override val buildType = null

    class Result(
        val allMetadataJarPath: Path?,
        val allMetadataSourcesJarPath: Path?,
        val module: AmperModule,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)
}