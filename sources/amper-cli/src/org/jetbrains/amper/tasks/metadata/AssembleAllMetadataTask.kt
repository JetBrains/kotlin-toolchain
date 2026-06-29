/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.metadata.json.projectStructure.KotlinProjectStructureMetadata
import org.jetbrains.amper.dependency.resolution.metadata.json.projectStructure.ProjectStructure
import org.jetbrains.amper.dependency.resolution.metadata.json.projectStructure.SourceSet
import org.jetbrains.amper.dependency.resolution.metadata.json.projectStructure.Variant
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.allFragmentDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.MetadataCompileTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.div

class AssembleAllMetadataTask (
    override val taskName: TaskName,
    override val module: AmperModule,
    private val moduleDependencies: ModuleDependencies,
    private val userCacheRoot: AmperUserCacheRoot,
    private val tempRoot: AmperProjectTempRoot,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
): ArtifactTaskBase(), BuildTask {

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext,
    ): TaskResult {
        check(module.leafPlatforms.size > 1) { "Running the task ${taskName.id} for a single-platform module is not allowed" }

        logger.infoNoConsole("Assembling all metadata artifact for module ${module.userReadableName}")

        // todo (AB): [AMPER-719] Add a test for the case where cinterop is declared for the fragment,
        //  but there is no actual sources are attached to the fragment except cinterop defs.
        //  (metadata compilation result is empty directory,
        //  but cinterop sourceSet is there and should be mentioned in the module descriptor)

        val metadataCompilations = dependenciesResult.filterIsInstance<MetadataCompileTask.Result>()
        val fragmentMetadata: Map<Fragment, MetadataCompileTask.Result> =
            metadataCompilations.associateBy { it.fragment }

        generateKotlinProjectDescriptor(fragmentMetadata)

        return Result(
            allMetadataOutputRoot = taskOutputRoot.path,
            module = module
        )
    }

    private suspend fun generateKotlinProjectDescriptor(fragmentMetadata: Map<Fragment, MetadataCompileTask.Result>) {
        // There is an entry for each module LEAF fragment in the project descriptor file.
        // Each entry contains a name of the variant from the Gradle metadata file that corresponds to the fragment
        // and a list of multiplatform fragments (represented with [SourceSet] this fragment depends on)
        val variants = module.leafFragments
            .filterNot { it.isTest }
            .map { it.toVariant() }
            .sortedBy { it.name }

        // intermediate source sets are declared in 'sourceSets' sections
        val sourceSets = fragmentMetadata
            .map { it.key.toSourceSet() }
            .sortedBy { it.name }

        val projectStructure = KotlinProjectStructureMetadata(
            ProjectStructure(
                formatVersion = "0.3.3",
                isPublishedAsRoot = "true",
                variants = variants,
                sourceSets = sourceSets,
            )
        )

        taskOutputRoot.path.createDirectories()

        withContext(Dispatchers.IO) {
            Files.writeString(
                taskOutputRoot.path / KOTLIN_PROJECT_STRUCTURE_METADATA_FILE_NAME,
                json.encodeToString(projectStructure),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }

    private fun Fragment.toVariant() = Variant(
        name = "${name}ApiElements",
        sourceSet = allFragmentDependencies(dependencyType = FragmentDependencyType.REFINE)
            .mapNotNull { it.takeIf { it.platforms.size > 1 }?.sourceSetName() }
            .toList()
    )

    private fun Fragment.toSourceSet() = SourceSet(
        name = sourceSetName(),
        dependsOn = fragmentDependencies.filter { it.type == FragmentDependencyType.REFINE }
            .map { it.target.sourceSetName() }
            .toList(),
        moduleDependency = moduleDependencies
            .getDirectCompileDependenciesCoordinates(isTest, platforms)
            .map { "${it.groupId}:${it.artifactId}"}
            .distinct(),
        sourceSetCInteropMetadataDirectory = "${sourceSetName()}-cinterop",
        binaryLayout = "klib",
        hostSpecific = null,
    )

    private fun Fragment.sourceSetName(): String {
        check(!isTest) { "Test fragments are not a part of all metadata, only main fragments are allowed" }
        return if (name.endsWith("Main")) name else "${name}Main"
    }

    override val isTest = false
    override val platform = Platform.COMMON
    override val buildType = null

    class Result(
        val allMetadataOutputRoot: Path,
        val module: AmperModule,
    ) : TaskResult

    companion object {
        private const val KOTLIN_PROJECT_STRUCTURE_METADATA_FILE_NAME = "kotlin-project-structure-metadata.json"
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}