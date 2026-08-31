/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.web

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.terminal.printCompilationSuccess
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.KotlinCompilationType
import org.jetbrains.amper.compilation.KotlinUserSettings
import org.jetbrains.amper.compilation.ResolvedCompilerPlugin
import org.jetbrains.amper.compilation.compiler.downloadKotlinCompiler
import org.jetbrains.amper.compilation.downloadCompilerPlugins
import org.jetbrains.amper.compilation.kotlinModuleName
import org.jetbrains.amper.compilation.serializableKotlinSettings
import org.jetbrains.amper.compilation.singleLeafFragment
import org.jetbrains.amper.compilation.validSourceFileExtensions
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.jdkSettings
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.getJdkOrUserError
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.SourceRoot
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.KotlinJavaSourceDirArtifact
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.identificationPhrase
import org.jetbrains.amper.tasks.native.filterKLibs
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.pathString
import kotlin.io.path.walk

internal abstract class WebCompileKlibTask(
    override val module: AmperModule,
    override val platform: Platform,
    private val userCacheRoot: AmperUserCacheRoot,
    private val jdkProvider: JdkProvider,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    private val tempRoot: AmperProjectTempRoot,
    override val isTest: Boolean,
    override val buildType: BuildType? = null,
    private val kotlinArtifactsDownloader: KotlinArtifactsDownloader =
        KotlinArtifactsDownloader(userCacheRoot, incrementalCache),
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
) : ArtifactTaskBase(), BuildTask {

    abstract val expectedPlatform: Platform

    init {
        require(platform.isLeaf)
        require(platform.isDescendantOf(expectedPlatform))
    }

    private val additionalKotlinJavaSourceDirs by Selectors.fromMatchingFragments(
        type = KotlinJavaSourceDirArtifact::class,
        module = module,
        isTest = isTest,
        hasPlatforms = setOf(platform),
        quantifier = Quantifier.AnyOrNone,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        val fragments = module.fragments.filter {
            it.platforms.contains(platform) && it.isTest == isTest
        }
        if (fragments.isEmpty()) {
            error("Zero fragments in module ${module.userReadableName} for platform $platform isTest=$isTest")
        }

        // TODO The IR compiler needs recursive dependencies
        val externalDependencies = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .flatMap { it.compileClasspath }
            .distinct()
            .filterKLibs()
            .toList()

        logger.debug(
            "" +
                    "${expectedPlatform.name} compile ${module.userReadableName} -- collected external dependencies" +
                    if (externalDependencies.isNotEmpty()) "\n" else "" +
                            externalDependencies.sorted().joinToString("\n").prependIndent("  ")
        )

        val compileModuleDependencies = dependenciesResult.filterIsInstance<Result>()

        val compiledKlibModuleDependencies = compileModuleDependencies
            .mapNotNull { it.compiledKlib }
            .toList()

        val kotlinUserSettings = fragments.singleLeafFragment().serializableKotlinSettings()

        val jdk = jdkProvider.getJdkOrUserError(module.jdkSettings)

        val libraryPaths = compiledKlibModuleDependencies + externalDependencies

        val additionalSources = additionalKotlinJavaSourceDirs.map { artifact ->
            SourceRoot(
                fragmentName = artifact.fragmentName,
                path = artifact.path,
            )
        }

        val productionCompileResult = if (isTest) {
            compileModuleDependencies.firstOrNull { it.module == module && !it.isTest }
                ?: error("${expectedPlatform.name} compilation result from production compilation result was not found for module=${module.userReadableName}, task=$taskName")
        } else null

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

            val artifact = taskOutputRoot.path

            // in Kotlin >= 2.2, we need to list all source files (not just dirs)
            val sourceFiles = sources.flatMap { dir ->
                // Kotlin IR compiler only accepts *.kt files, and we need to align with fragment arguments
                dir.walk().filter { it.extension in validSourceFileExtensions }
            }
            if (sourceFiles.isEmpty()) {
                logger.debug("No sources were found for ${fragments.identificationPhrase()}, skipping compilation")
                return@execute IncrementalCache.ExecutionResult(emptyList())
            }

            logger.infoNoConsole("Compiling module '${module.userReadableName}' for platform '${platform.pretty}'...")

            compileSources(
                jdk = jdk,
                kotlinUserSettings = kotlinUserSettings,
                sourceFiles = sourceFiles,
                fragments = fragments,
                additionalSourceRoots = additionalSources,
                librariesPaths = libraryPaths,
                friendPaths = listOfNotNull(productionCompileResult?.compiledKlib),
            )

            terminal.printCompilationSuccess(module, platform, isTest)

            return@execute IncrementalCache.ExecutionResult([artifact])
        }.outputFiles.singleOrNull()

        return Result(
            compiledKlib = artifact?.resolve(module.kotlinModuleName(isTest) + ".klib"),
            module = module,
            isTest = isTest,
            taskId = taskName.id,
            platform = platform,
        )
    }

    context(_: ProblemReporter)
    private suspend fun compileSources(
        jdk: Jdk,
        kotlinUserSettings: KotlinUserSettings,
        sourceFiles: List<Path>,
        fragments: List<Fragment>,
        additionalSourceRoots: List<SourceRoot>,
        librariesPaths: List<Path>,
        friendPaths: List<Path>,
    ) {
        val compiler = kotlinArtifactsDownloader.downloadKotlinCompiler(
            version = kotlinUserSettings.compilerVersion,
            jdk = jdk,
        )
        val compilerPlugins = kotlinArtifactsDownloader.downloadCompilerPlugins(
            plugins = kotlinUserSettings.compilerPlugins,
            repositories = module.mavenResolveRepositories.map { it.toRepository() },
        )

        val compilerArgs = kotlinCompilerArgs(
            kotlinUserSettings = kotlinUserSettings,
            compilerPlugins = compilerPlugins,
            libraryPaths = librariesPaths,
            outputPath = taskOutputRoot.path,
            friendPaths = friendPaths,
            fragments = fragments,
            sourceFiles = sourceFiles,
            additionalSourceRoots = additionalSourceRoots,
            moduleName = module.kotlinModuleName(isTest),
            include = null,
            compilationType = KotlinCompilationType.LIBRARY,
        )
        spanBuilder("kotlin-${expectedPlatform.name.lowercase()}-compilation")
            .setAmperModule(module)
            .setListAttribute("source-dirs", sourceFiles.map { it.pathString })
            .setAttribute("compiler-version", kotlinUserSettings.compilerVersion)
            .setListAttribute("compiler-args", compilerArgs)
            .use {
                logger.infoNoConsole("Compiling module '${module.userReadableName}' for platform '${platform.pretty}'...")
                val result = context(processRunner) {
                    compiler.compileWeb(
                        compilerArgs = compilerArgs,
                        argsMode = ArgsMode.ArgFile(tempRoot = tempRoot),
                        webPlatform = expectedPlatform,
                    )
                }
                if (result.exitCode != 0) {
                    userReadableError("Kotlin ${expectedPlatform.name} compilation failed (see errors above)")
                }
            }
    }

    internal abstract fun kotlinCompilerArgs(
        kotlinUserSettings: KotlinUserSettings,
        compilerPlugins: List<ResolvedCompilerPlugin>,
        libraryPaths: List<Path>,
        outputPath: Path,
        friendPaths: List<Path>,
        fragments: List<Fragment>,
        sourceFiles: List<Path>,
        additionalSourceRoots: List<SourceRoot>,
        moduleName: String,
        compilationType: KotlinCompilationType,
        include: Path?,
    ): List<String>

    class Result(
        /**
         * Resulting file path produced by the KLIB compilation stage for JS and Wasm.
         *
         * Null indicates there were no sources for this module (matching the selected fragments),
         * so compilation was skipped and no KLIB was generated.
         */
        val compiledKlib: Path?,
        val module: AmperModule,
        val isTest: Boolean,
        val taskId: TaskId,
        val platform: Platform,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)
}
