/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.web

import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.telemetry.setAmperModule
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
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.getJdkOrUserError
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.SourceRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.identificationPhrase
import org.jetbrains.amper.tasks.native.filterKLibs
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

internal abstract class WebLinkTask(
    override val module: AmperModule,
    override val platform: Platform,
    private val userCacheRoot: AmperUserCacheRoot,
    private val jdkProvider: JdkProvider,
    private val buildOutputRoot: AmperBuildOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    private val tempRoot: AmperProjectTempRoot,
    override val isTest: Boolean,
    override val buildType: BuildType,
    private val processRunner: ProcessRunner,
    /**
     * The name of the task that produces the klib for the sources of this module.
     */
    val compileKLibTaskId: TaskId,
    /**
     * Task names that produce klibs that need to be exposed as API in the resulting artifact.
     */
    private val kotlinArtifactsDownloader: KotlinArtifactsDownloader =
        KotlinArtifactsDownloader(userCacheRoot, incrementalCache),
) : ArtifactTaskBase(), BuildTask {

    abstract val expectedPlatform: Platform

    init {
        require(platform.isLeaf)
        require(platform.isDescendantOf(expectedPlatform))
    }

    private val compiledWebArtifact by CompiledWebArtifact(
        buildOutputRoot = buildOutputRoot,
        module = module,
        platform = platform,
        isTest = isTest,
        buildType = buildType,
    )

    val taskOutputRoot
        get() = compiledWebArtifact.path

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): Result {
        val fragments = module.fragments.filter {
            it.platforms.contains(platform) && it.isTest == isTest
        }
        if (fragments.isEmpty()) {
            error("Zero fragments in module ${module.userReadableName} for platform $platform isTest=$isTest")
        }

        val externalKLibs = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .flatMap { it.runtimeClasspath } // runtime dependencies including transitive
            .distinct()
            .filterKLibs()
            .toList()

        val includeArtifact = dependenciesResult
            .filterIsInstance<WebCompileKlibTask.Result>()
            .firstOrNull { it.taskId == compileKLibTaskId }
            ?.compiledKlib

        if (includeArtifact == null && isTest) {
            // We may skip linking for test specifically if there's no compiled code in the fragments.
            // Libraries are of no interest here because they can't contain any tests
            logger.debug("No test code was found compiled for ${fragments.identificationPhrase()}, skipping linking")
            return Result(
                linkedBinary = null,
            )
        }

        val compileKLibDependencies = dependenciesResult
            .filterIsInstance<WebCompileKlibTask.Result>()
            .filter { it.taskId != compileKLibTaskId }

        val compiledKLibs = compileKLibDependencies.mapNotNull { it.compiledKlib }

        val kotlinUserSettings = fragments.singleLeafFragment().serializableKotlinSettings()
        val jdk = jdkProvider.getJdkOrUserError(module.jdkSettings)

        logger.debug("${expectedPlatform.name} link '${module.userReadableName}' -- ${fragments.joinToString(" ") { it.name }}")

        val inputs = compiledKLibs + listOfNotNull(includeArtifact)

        val artifact = incrementalCache.executeForFiles(
            key = taskName.id.value,
            inputValues = mapOf(
                "kotlin.settings" to Json.encodeToString<KotlinUserSettings>(kotlinUserSettings),
                "task.output.root" to taskOutputRoot.pathString,
            ),
            inputFiles = inputs,
        ) {
            if (!kotlinUserSettings.compileIncrementally) { // we keep compiler outputs to update incrementally
                compiledWebArtifact.kotlinCompilerOutputRoot.clean()
                compiledWebArtifact.kotlinIcDataDir.clean()
            }

            val artifactPath = compiledWebArtifact.kotlinCompilerOutputRoot

            compileSources(
                jdk,
                kotlinUserSettings = kotlinUserSettings,
                librariesPaths = externalKLibs + inputs,
                includeArtifact = includeArtifact,
                compileIncrementally = kotlinUserSettings.compileIncrementally,
            )

            listOf(artifactPath)
        }.single()

        return Result(
            linkedBinary = artifact,
        )
    }

    context(_: ProblemReporter)
    private suspend fun compileSources(
        jdk: Jdk,
        kotlinUserSettings: KotlinUserSettings,
        librariesPaths: List<Path>,
        includeArtifact: Path?,
        compileIncrementally: Boolean,
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
            outputPath = compiledWebArtifact.kotlinCompilerOutputRoot,
            friendPaths = emptyList(),
            fragments = emptyList(),
            sourceFiles = emptyList(),
            additionalSourceRoots = emptyList(),
            moduleName = module.kotlinModuleName(isTest),
            compilationType = KotlinCompilationType.BINARY,
            buildType = buildType,
            include = includeArtifact,
            cacheDirectory = compiledWebArtifact.kotlinIcDataDir.takeIf {
                compileIncrementally && buildType == BuildType.Debug
            },
        )

        if (isTest) {
            logger.debug("Linking ${expectedPlatform.name} test executable for module '${module.userReadableName}' on platform '${platform.pretty}'...")
        } else {
            logger.infoNoConsole("Linking ${platform.pretty} executable for module '${module.userReadableName}'...")
        }
        spanBuilder("kotlin-${expectedPlatform.name.lowercase()}-link")
            .setAmperModule(module)
            .setAttribute("compiler-version", kotlinUserSettings.compilerVersion)
            .setListAttribute("compiler-args", compilerArgs)
            .use {
                val result = context(processRunner) {
                    compiler.compileWeb(
                        compilerArgs = compilerArgs,
                        argsMode = ArgsMode.ArgFile(tempRoot = tempRoot),
                        webPlatform = expectedPlatform,
                    )
                }
                if (result.exitCode != 0) {
                    userReadableError("Kotlin ${expectedPlatform.name} linking failed (see errors above)")
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
        buildType: BuildType,
        include: Path?,
        cacheDirectory: Path?,
    ): List<String>

    class Result(
        /**
         * Resulting file path produced by the link stage for JS or Wasm compilation.
         *
         * Null indicates a test compilation where no main module KLIB was produced
         * (i.e., linking is skipped because there is no compiled test code to link).
         */
        val linkedBinary: Path?,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)
}
