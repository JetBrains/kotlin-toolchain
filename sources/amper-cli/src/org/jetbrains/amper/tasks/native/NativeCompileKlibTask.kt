/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.terminal.printCompilationSuccess
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.KotlinCompilationType
import org.jetbrains.amper.compilation.downloadCompilerPlugins
import org.jetbrains.amper.compilation.downloadNativeCompiler
import org.jetbrains.amper.compilation.kotlinNativeCompilerArgs
import org.jetbrains.amper.compilation.serializableKotlinSettings
import org.jetbrains.amper.compilation.singleLeafFragment
import org.jetbrains.amper.compilation.validSourceFileExtensions
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.frontend.friends
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.mavenResolveRepositories
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.SourceRoot
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.CinteropKlibsArtifact
import org.jetbrains.amper.tasks.artifacts.KotlinJavaSourceDirArtifact
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.identificationPhrase
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.pathString
import kotlin.io.path.walk

internal class NativeCompileKlibTask(
    override val module: AmperModule,
    override val platform: Platform,
    private val userCacheRoot: AmperUserCacheRoot,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    private val tempRoot: AmperProjectTempRoot,
    override val isTest: Boolean,
    override val buildType: BuildType,
    private val kotlinArtifactsDownloader: KotlinArtifactsDownloader =
        KotlinArtifactsDownloader(userCacheRoot, incrementalCache),
    private val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
): ArtifactTaskBase(), BuildTask {
    init {
        require(platform.isLeaf)
        require(platform.isDescendantOf(Platform.NATIVE))
    }

    private val additionalKotlinJavaSourceDirs by Selectors.fromMatchingFragments(
        type = KotlinJavaSourceDirArtifact::class,
        module = module,
        isTest = isTest,
        hasPlatforms = setOf(platform),
        quantifier = Quantifier.AnyOrNone,
    )

    private val cinteropKlibs by Selectors.fromModuleWithDependencies(
        type = CinteropKlibsArtifact::class,
        module = module,
        isTest = isTest,
        platform = platform,
        userCacheRoot = userCacheRoot,
        incrementalCache = incrementalCache,
        quantifier = Quantifier.AnyOrNone,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val leafFragment = module.leafFragments.single { it.platform == platform && it.isTest == isTest }
        val fragments = module.fragments.filter {
            it.platforms.contains(platform) && it.isTest == isTest
        }
        if (fragments.isEmpty()) {
            error("Zero fragments in module ${module.userReadableName} for platform $platform isTest=$isTest")
        }

        val externalDependencies = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .flatMap { it.compileClasspath } // compiler dependencies including transitive
            .distinct()
            .filterKLibs()
            .toList()

        logger.debug("" +
                "native compile ${module.userReadableName} -- collected external dependencies" +
                if (externalDependencies.isNotEmpty()) "\n" else "" +
                externalDependencies.sorted().joinToString("\n").prependIndent("  ")
        )

        val compileResults = dependenciesResult.filterIsInstance<Result>()

        val friendCompileResults = leafFragment.friends.map { friendFragment ->
            compileResults.findResultForFragment(friendFragment)
        }

        val compiledModuleDependencies = compileResults
            .mapNotNull { it.compiledKlib }
            .toList()

        // todo native resources are what exactly?

        val kotlinUserSettings = fragments.singleLeafFragment().serializableKotlinSettings()

        logger.debug("native compile klib '${module.userReadableName}' -- ${fragments.joinToString(" ") { it.name }}")

        val libraryPaths = compiledModuleDependencies + externalDependencies +
                cinteropKlibs.flatMap { it.allKlibs() }

        val additionalSources = additionalKotlinJavaSourceDirs.map { artifact ->
            SourceRoot(
                fragmentName = artifact.fragmentName,
                path = artifact.path,
            )
        }

        val sources = fragments.flatMap { it.sourceRoots } + additionalSources.map { it.path }

        val artifact = incrementalCache.execute(
            key = taskName.id.value,
            inputValues = mapOf(
                "kotlin.settings" to Json.encodeToString(kotlinUserSettings),
                "task.output.root" to taskOutputRoot.path.pathString,
            ),
            inputFiles = sources + libraryPaths,
        ) {
            taskOutputRoot.path.clean()

            // in Kotlin >= 2.2, we need to list all source files (not just dirs)
            val sourceFiles = sources.flatMap { dir ->
                // konanc only accepts *.kt files, and we need to align with fragment arguments
                dir.walk().filter { it.extension in validSourceFileExtensions }
            }
            if (sourceFiles.isEmpty()) {
                logger.debug("No sources were found for ${fragments.identificationPhrase()}, skipping compilation")
                return@execute IncrementalCache.ExecutionResult(emptyList())
            }

            val artifact = taskOutputRoot.path.resolve(KotlinCompilationType.LIBRARY.outputFilename(module, platform, isTest))

            val nativeCompiler = downloadNativeCompiler(kotlinUserSettings.compilerVersion, userCacheRoot, jdkProvider)
            val compilerPlugins = kotlinArtifactsDownloader.downloadCompilerPlugins(
                plugins = kotlinUserSettings.compilerPlugins,
                repositories = module.mavenResolveRepositories.map { it.toRepository() },
            )
            val args = kotlinNativeCompilerArgs(
                buildType = buildType,
                kotlinUserSettings = kotlinUserSettings,
                compilerPlugins = compilerPlugins,
                entryPoint = null,
                libraryPaths = libraryPaths,
                exportedLibraryPaths = [],
                friendPaths = friendCompileResults.mapNotNull { it.compiledKlib },
                fragments = fragments,
                fragmentPlatforms = setOf(platform),
                sourceFiles = sourceFiles,
                additionalSourceRoots = additionalSources,
                binaryOptions = emptyMap(),
                outputPath = artifact,
                compilationType = KotlinCompilationType.LIBRARY,
                include = null,
            )

            // only for the log file, because animations take care of this
            logger.infoNoConsole("Compiling module '${module.userReadableName}' for platform '${platform.pretty}'...")
            nativeCompiler.compile(processRunner, args, tempRoot, module)

            terminal.printCompilationSuccess(module, platform, isTest)
            return@execute IncrementalCache.ExecutionResult(listOf(artifact))
        }.outputFiles.singleOrNull()

        return Result(
            compiledKlib = artifact,
            dependencyKlibs = libraryPaths,
            fragment = leafFragment,
            taskId = taskName.id,
            platform = platform,
        )
    }

    private fun List<Result>.findResultForFragment(f: Fragment) =
        firstOrNull { it.fragment == f }
            ?: error("Compilation result not found for dependency fragment ${f.module.userReadableName}/${f.name}")

    class Result(
        val compiledKlib: Path?,
        val dependencyKlibs: List<Path>,
        val fragment: LeafFragment,
        val taskId: TaskId,
        val platform: Platform,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)
}
