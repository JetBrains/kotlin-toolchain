/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import com.github.ajalt.mordant.terminal.Terminal
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanBuilder
import kotlinx.serialization.json.Json
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.BuildPrimitives
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.context.AmperProjectRoot
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.logging.withoutConsoleLogging
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.telemetry.setFragments
import org.jetbrains.amper.cli.terminal.printCompilationSuccess
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.CompilationUserSettings
import org.jetbrains.amper.compilation.CompilerBuildProblem
import org.jetbrains.amper.compilation.JavaUserSettings
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.ProblemReportingCompilerMessageRenderer
import org.jetbrains.amper.compilation.bta.CombiningKotlinLogger
import org.jetbrains.amper.compilation.bta.CompilationDetectingBuildMetricsCollector
import org.jetbrains.amper.compilation.bta.ErrorsCollectorKotlinLogger
import org.jetbrains.amper.compilation.bta.OpenTelemetryBuildMetricsCollector
import org.jetbrains.amper.compilation.bta.TerminalPrintingKotlinLogger
import org.jetbrains.amper.compilation.bta.asKotlinLogger
import org.jetbrains.amper.compilation.bta.loadMaybeCachedImpl
import org.jetbrains.amper.compilation.bta.makeClasspathEntrySnapshot
import org.jetbrains.amper.compilation.bta.metrics.CombiningMetricsCollector
import org.jetbrains.amper.compilation.downloadCompilerPlugins
import org.jetbrains.amper.compilation.kotlinJvmCompilerArgs
import org.jetbrains.amper.compilation.kotlinModuleName
import org.jetbrains.amper.compilation.serializableCompilationSettings
import org.jetbrains.amper.compilation.singleLeafFragment
import org.jetbrains.amper.concurrency.mapConcurrently
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.aomBuilder.javaAnnotationProcessingGeneratedSourcesPath
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.frontend.jdkSettings
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.getJdkOrUserError
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.plus
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.withJavaArgFile
import org.jetbrains.amper.tasks.ClasspathProvider
import org.jetbrains.amper.tasks.CommonTaskUtils.userReadableList
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.SourceRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.JvmResourcesDirArtifact
import org.jetbrains.amper.tasks.artifacts.KotlinJavaSourceDirArtifact
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.identificationPhrase
import org.jetbrains.amper.tasks.java.JavaAnnotationProcessorClasspathTask
import org.jetbrains.amper.tasks.maven.MavenPhaseResult
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation.Companion.COMPILER_MESSAGE_RENDERER
import org.jetbrains.kotlin.buildtools.api.BaseIncrementalCompilationConfiguration.Companion.MODULE_BUILD_DIR
import org.jetbrains.kotlin.buildtools.api.BaseIncrementalCompilationConfiguration.Companion.ROOT_PROJECT_DIR
import org.jetbrains.kotlin.buildtools.api.BaseIncrementalCompilationConfiguration.Companion.TRACK_CONFIGURATION_INPUTS
import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult.COMPILATION_SUCCESS
import org.jetbrains.kotlin.buildtools.api.CompilerArgumentsParseException
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.getToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation.Companion.INCREMENTAL_COMPILATION
import org.jetbrains.kotlin.buildtools.api.jvm.operations.snapshotBasedIcConfiguration
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.walk

/**
 * Incremental-cache output key used to persist compiler messages produced during JVM compilation.
 *
 * This allows replaying them in case of a cache hit.
 *
 * @see JvmCompileTask.replayCachedCompilerBuildProblems
 */
private const val JVM_COMPILER_BUILD_PROBLEMS_OUTPUT_KEY = "jvmCompilerBuildProblems"

@OptIn(ExperimentalBuildToolsApi::class)
internal class JvmCompileTask(
    override val module: AmperModule,
    override val isTest: Boolean,
    private val fragments: List<Fragment>,
    private val userCacheRoot: AmperUserCacheRoot,
    private val projectRoot: AmperProjectRoot,
    private val tempRoot: AmperProjectTempRoot,
    override val taskName: TaskName,
    private val incrementalCache: IncrementalCache,
    private val kotlinArtifactsDownloader: KotlinArtifactsDownloader =
        KotlinArtifactsDownloader(userCacheRoot, incrementalCache),
    private val buildOutputRoot: AmperBuildOutputRoot,
    private val jdkProvider: JdkProvider,
    override val buildType: BuildType? = null,
    override val platform: Platform = Platform.JVM,
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
    private val openTelemetry: OpenTelemetry,
) : ArtifactTaskBase(), BuildTask {

    init {
        require(platform == Platform.JVM || platform == Platform.ANDROID) {
            "Illegal platform for JvmCompileTask: $platform"
        }
    }

    private val compiledJvmArtifact by CompiledJvmArtifact(
        buildOutputRoot = buildOutputRoot,
        module = module,
        platform = platform,
        isTest = isTest,
        buildType = buildType,
    )

    private val additionalKotlinJavaSourceDirs by Selectors.fromMatchingFragments(
        type = KotlinJavaSourceDirArtifact::class,
        module = module,
        isTest = isTest,
        hasPlatforms = setOf(platform),
        quantifier = Quantifier.AnyOrNone,
    )

    private val additionalResourcesDirs by Selectors.fromMatchingFragments(
        type = JvmResourcesDirArtifact::class,
        module = module,
        isTest = isTest,
        hasPlatforms = setOf(platform),
        quantifier = Quantifier.AnyOrNone,
    )

    val taskOutputRoot get() = compiledJvmArtifact.path

    // This path is voluntarily not tied to the current module, so the snapshots are shared
    private val icSnapshotsDir: Path = buildOutputRoot.path / "ic-cache/classpath-snapshots"

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        require(fragments.isNotEmpty()) {
            "fragments list is empty for jvm compile task, module=${module.userReadableName}"
        }

        logger.debug("compile ${module.userReadableName} -- ${fragments.userReadableList()}")

        val mavenDependencies = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .singleOrNull()
            ?: error("Expected one and only one dependency on (${ResolveExternalDependenciesTask.Result::class.java.simpleName}) input, but got: ${dependenciesResult.joinToString { it.javaClass.simpleName }}")

        val compileModuleDependencies = dependenciesResult.filterIsInstance<Result>()
        val javaAnnotationProcessorClasspath = dependenciesResult
            .filterIsInstance<JavaAnnotationProcessorClasspathTask.Result>()
            .singleOrNull()
            ?.processorClasspath
            ?: emptyList()

        val productionJvmCompileResult = if (isTest) {
            compileModuleDependencies.firstOrNull { it.module == module && !it.isTest }
                ?: error("jvm compilation result from production compilation result was not found for module=${module.userReadableName}, task=$taskName")
        } else null

        val userSettings = fragments.singleLeafFragment().serializableCompilationSettings()

        val additionalClasspath = dependenciesResult.filterIsInstance<ClasspathProvider>().flatMap { it.compileClasspath }
        val classpath =
            compileModuleDependencies.flatMap { it.classesOutputRoots } + mavenDependencies.compileClasspath + additionalClasspath

        // Collect additional source roots.
        val additionalArtifactSources = additionalKotlinJavaSourceDirs.map { artifact ->
            SourceRoot(
                fragmentName = artifact.fragmentName,
                path = artifact.path,
            )
        }
        val additionalSourceRootsFromMaven = dependenciesResult
            .filterIsInstance<MavenPhaseResult>()
            .flatMap { it.sourceRoots }
            .distinctBy { it.path } // Need to remove duplicates, because a same path can be provided by multiple providers.

        val additionalSources = additionalArtifactSources + additionalSourceRootsFromMaven

        val additionalResources = additionalResourcesDirs.map { artifact ->
            SourceRoot(
                fragmentName = artifact.fragmentName,
                path = artifact.path,
            )
        }

        val jdk = jdkProvider.getJdkOrUserError(jdkSettings = module.jdkSettings)

        val javaAnnotationProcessorsGeneratedDir =
            fragments.singleLeafFragment().javaAnnotationProcessingGeneratedSourcesPath(buildOutputRoot.path)

        val inputValues = mapOf(
            "jdk.version" to jdk.version,
            "jdk.home" to jdk.homeDir.pathString,
            "user.settings" to Json.encodeToString(userSettings),
            "task.output.root" to taskOutputRoot.pathString,
            "target.platforms" to module.leafPlatforms.map { it.name }.sorted().joinToString(),
            "java.annotation.processor.generated.dir" to javaAnnotationProcessorsGeneratedDir.pathString
        )

        val sources = fragments.flatMap { it.sourceRoots }.map { it.toAbsolutePath() } + additionalSources.map { it.path }
        val resources = fragments.map { it.resourcesPath }.map { it.toAbsolutePath() } + additionalResources.map { it.path }
        val inputFiles = sources + resources + classpath + javaAnnotationProcessorClasspath

        val result = incrementalCache.execute(taskName.id.value, inputValues, inputFiles) {
            javaAnnotationProcessorsGeneratedDir.deleteRecursively()
            compiledJvmArtifact.resourcesRoot.deleteRecursively() // we want to remove obsolete resources
            val compileJavaIncrementally =
                shouldCompileJavaIncrementally(userSettings.java, javaAnnotationProcessorClasspath)
            if (!compileJavaIncrementally) { // we keep compiler outputs to update incrementally
                compiledJvmArtifact.javaCompilerOutputRoot.deleteRecursively()
                compiledJvmArtifact.jicDataDir.deleteRecursively()
            }
            if (!userSettings.kotlin.compileIncrementally) { // we keep compiler outputs to update incrementally
                compiledJvmArtifact.kotlinCompilerOutputRoot.deleteRecursively()
                compiledJvmArtifact.kotlinIcDataDir.deleteRecursively()
            }

            val nonEmptySourceDirs = sources
                .filter {
                    when {
                        it.isDirectory() -> it.listDirectoryEntries().isNotEmpty()
                        it.exists() ->
                            error("Source directory at '$it' exists, but it's not a directory, this is currently unsupported")
                        else -> false
                    }
                }

            val outputPaths = mutableListOf<Path>()
            val allCompilerProblems = mutableListOf<CompilerBuildProblem>()

            if (nonEmptySourceDirs.isNotEmpty()) {
                logger.infoNoConsole("Compiling module '${module.userReadableName}' for platform '${platform.pretty}'...")
                val result = compileSources(
                    jdk = jdk,
                    sourceDirectories = nonEmptySourceDirs,
                    additionalSources = additionalSources,
                    userSettings = userSettings,
                    classpath = classpath,
                    friendPaths = productionJvmCompileResult?.classesOutputRoots.orEmpty(),
                    javaAnnotationProcessorClasspath = javaAnnotationProcessorClasspath,
                    javaAnnotationProcessorsGeneratedDir = javaAnnotationProcessorsGeneratedDir,
                    tempRoot = tempRoot,
                )
                allCompilerProblems.addAll(result.problems)
                if (compiledJvmArtifact.kotlinCompilerOutputRoot.exists()) {
                    outputPaths.add(compiledJvmArtifact.kotlinCompilerOutputRoot.toAbsolutePath())
                }
                if (compiledJvmArtifact.javaCompilerOutputRoot.exists()) {
                    outputPaths.add(compiledJvmArtifact.javaCompilerOutputRoot.toAbsolutePath())
                }
                if (javaAnnotationProcessorsGeneratedDir.exists()) {
                    outputPaths.add(javaAnnotationProcessorsGeneratedDir)
                }
                if (result.didCompileSomething) {
                    terminal.printCompilationSuccess(module, platform, isTest)
                } else {
                    logger.debug("Up-to-date compilation result for ${fragments.identificationPhrase()}")
                }
            } else {
                logger.debug("No sources were found for ${fragments.identificationPhrase()}, skipping compilation")
            }

            val presentResources = resources.filter { it.exists() }
            if (presentResources.isNotEmpty()) {
                compiledJvmArtifact.resourcesRoot.createDirectories()
                outputPaths.add(compiledJvmArtifact.resourcesRoot.toAbsolutePath())
            }
            for (resource in presentResources) {
                val dest = if (resource.isDirectory()) {
                    compiledJvmArtifact.resourcesRoot
                } else {
                    compiledJvmArtifact.resourcesRoot / resource.fileName
                }
                logger.debug("Copying resources from '{}' to '{}'...", resource, dest)

                // if we compile incrementally, then we don't clean the output dir => overwrite instead of failing that a file exists
                val overwrite = compileJavaIncrementally || userSettings.kotlin.compileIncrementally
                BuildPrimitives.copy(from = resource, to = dest, overwrite = overwrite)
            }

            return@execute IncrementalCache.ExecutionResult(
                outputFiles = outputPaths,
                outputValues = mapOf(
                    JVM_COMPILER_BUILD_PROBLEMS_OUTPUT_KEY to Json.encodeToString(allCompilerProblems),
                ),
            )
        }
        if (result.loadedFromCache) {
            replayCachedCompilerBuildProblems(result)
        }

        return Result(
            classesOutputRoots = listOf(
                compiledJvmArtifact.javaCompilerOutputRoot,
                compiledJvmArtifact.kotlinCompilerOutputRoot,
                compiledJvmArtifact.resourcesRoot,
            )
                .filter { it.exists() }
                .map { it.toAbsolutePath() },
            module = module,
            isTest = isTest,
            changes = result.changes,
        )
    }

    context(_: ProblemReporter)
    private suspend fun compileSources(
        jdk: Jdk,
        sourceDirectories: List<Path>,
        additionalSources: List<SourceRoot>,
        userSettings: CompilationUserSettings,
        classpath: List<Path>,
        friendPaths: List<Path>,
        javaAnnotationProcessorClasspath: List<Path>,
        tempRoot: AmperProjectTempRoot,
        javaAnnotationProcessorsGeneratedDir: Path,
    ): CompilationResult {
        for (friendPath in friendPaths) {
            require(classpath.contains(friendPath)) {
                "The classpath must contain all friend paths, but '$friendPath' is not in '${classpath.joinToString(File.pathSeparator)}'"
            }
        }

        val sourcesFiles = sourceDirectories.flatMap { it.walk() }
        val kotlinFilesToCompile = sourcesFiles.filter { it.extension == "kt" }
        val javaFilesToCompile = sourcesFiles.filter { it.extension == "java" }
        val allCompilerProblems = mutableListOf<CompilerBuildProblem>()
        var didCompileSomething = false

        if (kotlinFilesToCompile.isNotEmpty()) {
            // Enable multi-platform support only if targeting other than JVM platforms
            // or having a common and JVM fragments (like src and src@jvm directories)
            val isMultiplatform = (module.leafPlatforms - Platform.JVM).isNotEmpty() || sourceDirectories.size > 1

            val result = compileKotlinSources(
                userSettings = userSettings,
                isMultiplatform = isMultiplatform,
                classpath = classpath,
                jdk = jdk,
                sourceFiles = kotlinFilesToCompile + javaFilesToCompile,
                additionalSourceRoots = additionalSources,
                friendPaths = friendPaths,
            )
            allCompilerProblems.addAll(result.problems)
            didCompileSomething = result.didCompileSomething
        }

        if (javaFilesToCompile.isNotEmpty()) {
            compileJavaSources(
                jdk = jdk,
                userSettings = userSettings,
                classpath = classpath + listOf(compiledJvmArtifact.kotlinCompilerOutputRoot),
                processorClasspath = javaAnnotationProcessorClasspath,
                processorGeneratedDir = javaAnnotationProcessorsGeneratedDir,
                javaSourceFiles = javaFilesToCompile,
                tempRoot = tempRoot,
            )
            didCompileSomething = true // we don't have the information for Java compilation for now
        }
        return CompilationResult(
            problems = allCompilerProblems,
            didCompileSomething = didCompileSomething,
        )
    }

    context(problemReporter: ProblemReporter)
    private suspend fun compileKotlinSources(
        userSettings: CompilationUserSettings,
        isMultiplatform: Boolean,
        classpath: List<Path>,
        jdk: Jdk,
        sourceFiles: List<Path>,
        additionalSourceRoots: List<SourceRoot>,
        friendPaths: List<Path>,
    ): CompilationResult {
        logger.debug("Compiling Kotlin/JVM sources for module '${module.userReadableName}'...")

        val kotlinToolchains = KotlinToolchains.loadMaybeCachedImpl(
            kotlinVersion = userSettings.kotlin.compilerVersion,
            downloader = kotlinArtifactsDownloader,
        )

        // TODO should we allow users to choose in-process vs daemon?
        // TODO settings for daemon JVM args?
        // FIXME Daemon strategy currently fails with "Can't get connection"
        val executionPolicy = kotlinToolchains.createInProcessExecutionPolicy()

        val errorsCollector = ErrorsCollectorKotlinLogger()
        val isCompilerMessageRendererAPIAvailable =
            ComparableVersion(userSettings.kotlin.compilerVersion) >= ComparableVersion("2.4.0-Beta2")
        val compilationLogger = CombiningKotlinLogger(buildList {
            add(logger.asKotlinLogger())
            add(errorsCollector)
            // Compiler message renderer is supported only since 2.4.0-Beta2.
            // For previous versions we simply print messages provided to KotlinLogger to the terminal with an
            // appropriate style.
            if (!isCompilerMessageRendererAPIAvailable) {
                add(TerminalPrintingKotlinLogger(terminal, module))
            }
        })

        val compilerPlugins = kotlinArtifactsDownloader.downloadCompilerPlugins(
            plugins = userSettings.kotlin.compilerPlugins,
            repositories = module.mavenResolveRepositories.map { it.toRepository() },
        )

        val compilerArgs = kotlinJvmCompilerArgs(
            moduleName = module.kotlinModuleName(isTest),
            isMultiplatform = isMultiplatform,
            userSettings = userSettings,
            classpath = classpath,
            jdkHome = jdk.homeDir,
            compilerPlugins = compilerPlugins,
            fragments = fragments,
            additionalSourceRoots = additionalSourceRoots,
            friendPaths = friendPaths,
        )

        val collectingMessageProcessor = CollectingProblemReporter()
        val compilationDetectingBuildMetricsCollector = CompilationDetectingBuildMetricsCollector()
        val openTelemetryBuildMetricsCollector = OpenTelemetryBuildMetricsCollector(openTelemetry)
        val metricsCollector = CombiningMetricsCollector(
            [
                compilationDetectingBuildMetricsCollector,
                openTelemetryBuildMetricsCollector
            ]
        )

        val kotlinCompilationResult = spanBuilder("kotlin-compilation")
            .setAmperModule(module)
            .setFragments(fragments)
            .setListAttribute("source-files", sourceFiles.map { it.pathString })
            .setListAttribute("compiler-args", compilerArgs)
            .setAttribute("compiler-version", userSettings.kotlin.compilerVersion)
            .use {
                // TODO maybe share the build session with the whole Amper build (across all JVM compile tasks)?
                kotlinToolchains.createBuildSession().use { session ->
                    val jvmToolchain = kotlinToolchains.getToolchain<JvmPlatformToolchain>()
                    val classpathSnapshots = session.makeClasspathSnapshots(
                        classpath = classpath,
                        outputDir = icSnapshotsDir,
                        kotlinLogger = compilationLogger,
                    )
                    val compilationOperation = jvmToolchain.jvmCompilationOperation(
                        sources = sourceFiles,
                        destinationDirectory = compiledJvmArtifact.kotlinCompilerOutputRoot,
                    ) {
                        try {
                            compilerArguments.applyArgumentStrings(compilerArgs)
                        } catch (e: CompilerArgumentsParseException) {
                            // The Build Tools API crashes in case of invalid compiler arguments instead of letting the
                            // compiler report the error, so we catch this exception to provide a nice user error.
                            userReadableError("Invalid compiler arguments: ${e.message}", cause = e)
                        }
                        if (isCompilerMessageRendererAPIAvailable) {
                            this[COMPILER_MESSAGE_RENDERER] = ProblemReportingCompilerMessageRenderer(
                                reporter = problemReporter + collectingMessageProcessor,
                                moduleName = module.userReadableName,
                            )
                        }
                        if (userSettings.kotlin.compileIncrementally) {
                            this[INCREMENTAL_COMPILATION] = snapshotBasedIcConfiguration(
                                workingDirectory = compiledJvmArtifact.kotlinIcDataDir.createDirectories(),
                                sourcesChanges = SourcesChanges.ToBeCalculated,
                                dependenciesSnapshotFiles = classpathSnapshots,
                            ) {
                                this[ROOT_PROJECT_DIR] = projectRoot.path

                                // Not the module's build dir because there is no such thing.
                                // This is the closest we have.
                                this[MODULE_BUILD_DIR] = buildOutputRoot.path

                                // Necessary to avoid a cache hit when important compiler args change (e.g. JVM target).
                                // Surprisingly, this is not the default!
                                // Note: has no effect in Kotlin >= 2.4.0, but we warn users about it in the frontend
                                this[TRACK_CONFIGURATION_INPUTS] = true
                            }
                        }
                        this[BuildOperation.METRICS_COLLECTOR] = metricsCollector
                    }
                    spanBuilder("BTA compilation operation").use {
                        session.executeOperation(
                            operation = compilationOperation,
                            executionPolicy = executionPolicy,
                            logger = compilationLogger,
                        ).also {
                            openTelemetryBuildMetricsCollector.reportSpans()
                        }
                    }
                }
            }

        if (kotlinCompilationResult != COMPILATION_SUCCESS) {
            userReadableError("Kotlin compilation failed with ${errorsCollector.errors.size} errors (see above)")
        }

        return CompilationResult(
            problems = collectingMessageProcessor.problems.map { it as CompilerBuildProblem },
            didCompileSomething = compilationDetectingBuildMetricsCollector.didCompileSomething,
        )
    }

    data class CompilationResult(
        val problems: List<CompilerBuildProblem>,
        val didCompileSomething: Boolean,
    )

    context(problemReporter: ProblemReporter)
    private fun replayCachedCompilerBuildProblems(result: IncrementalCache.ExecutionResult) {
        val serializedProblems = result.outputValues[JVM_COMPILER_BUILD_PROBLEMS_OUTPUT_KEY] ?: return
        val problems = try {
            Json.decodeFromString<List<CompilerBuildProblem>>(serializedProblems)
        } catch (e: Exception) {
            logger.warn("Failed to deserialize cached Kotlin compiler warnings for task '${taskName.id.value}'", e)
            return
        }
        if (problems.isEmpty()) return

        problems.forEach(problemReporter::reportMessage)
    }

    /**
     * Creates an ABI snapshot of the given [classpath], and writes the resulting files (per entry) in the given [outputDir].
     *
     * Currently, only JARs and directories of classes are supported.
     *
     * @return the paths to the saved snapshot files
     */
    @OptIn(ExperimentalBuildToolsApi::class)
    internal suspend fun KotlinToolchains.BuildSession.makeClasspathSnapshots(
        classpath: List<Path>,
        outputDir: Path,
        kotlinLogger: KotlinLogger?,
    ): List<Path> {
        val [snapshottableClasspath, nonSnapshottable] = classpath.partition { it.isDirectory() || it.extension == "jar" }
        nonSnapshottable.forEach {
            // We sometimes have .aar or .zip here. These are most likely bugs that we should investigate, not the
            // responsibility of the user, so there is no need for us to warn them, hence the file-only log.
            withoutConsoleLogging {
                logger.warn("Unsupported extension .${it.extension} for classpath snapshotting, skipping entry $it")
            }
        }
        return context(incrementalCache) {
            spanBuilder("Make classpath snapshot").use {
                snapshottableClasspath.mapConcurrently { entryPath ->
                    makeClasspathEntrySnapshot(
                        entryPath = entryPath,
                        entryMoniker = if (entryPath.isDirectory()) {
                            // Directories are kotlin-output and java-output under a module name (in general), so we
                            // prepend the module name to make it more recognizable
                            "${module.userReadableName}${if (isTest) "-test" else ""}-${entryPath.name}"
                        } else {
                            entryPath.name
                        },
                        outputDir = outputDir,
                        granularity = if (entryPath.isDirectory()) {
                            // Directories are local modules, we want high granularity at the cost of higher snapshot size
                            ClassSnapshotGranularity.CLASS_MEMBER_LEVEL
                        } else {
                            // JARs are usually external deps and change less often, so CLASS_LEVEL granularity is enough
                            // and we can save on size
                            ClassSnapshotGranularity.CLASS_LEVEL
                        },
                        logger = kotlinLogger,
                    )
                }
            }
        }
    }

    private suspend fun compileJavaSources(
        jdk: Jdk,
        userSettings: CompilationUserSettings,
        classpath: List<Path>,
        processorClasspath: List<Path>,
        javaSourceFiles: List<Path>,
        tempRoot: AmperProjectTempRoot,
        processorGeneratedDir: Path,
    ) {
        logger.debug("Compiling Java sources for module '${module.userReadableName}'...")

        // javac arguments that are common for plain javac and JIC
        val commonArgs = buildList {
            if (userSettings.jvmRelease != null) {
                add("--release")
                add(userSettings.jvmRelease.releaseNumber.toString())
            }

            if (userSettings.java.parameters) {
                add("-parameters")
            }

            // necessary for reproducible source jars across OS-es
            add("-encoding")
            add("utf-8")

            // TODO Should we move settings.kotlin.debug to settings.jvm.debug and use it here?
            add("-g")

            if (!processorClasspath.isEmpty()) {
                val annotationProcessorArgs = buildAnnotationProcessorArgs(userSettings.java, processorClasspath, processorGeneratedDir)
                addAll(annotationProcessorArgs)
            }
        }

        val freeCompilerArgs = userSettings.java.freeCompilerArgs

        val success = if (shouldCompileJavaIncrementally(userSettings.java, processorClasspath)) {
            compiledJvmArtifact.javaCompilerOutputRoot.createDirectories()
            compiledJvmArtifact.jicDataDir.createDirectories()
            val jicJavacArgs = commonArgs + freeCompilerArgs
            javacSpanBuilder(jicJavacArgs, jdk, incremental = true).use {
                compileJavaWithJic(
                    processRunner,
                    jdk,
                    module,
                    isTest,
                    javaSourceFiles,
                    jicJavacArgs,
                    compiledJvmArtifact.javaCompilerOutputRoot,
                    compiledJvmArtifact.jicDataDir,
                    classpath,
                    logger
                )
            }
        } else {
            compileJavaWithPlainJavac(tempRoot, jdk, commonArgs, classpath, freeCompilerArgs, javaSourceFiles)
        }
        if (!success) {
            userReadableError("Java compilation failed (see errors above)")
        }
    }

    private suspend fun compileJavaWithPlainJavac(
        tempRoot: AmperProjectTempRoot,
        jdk: Jdk,
        commonJavacArgs: List<String>,
        classpath: List<Path>,
        freeCompilerArgs: List<String>,
        javaSourceFiles: List<Path>,
    ): Boolean {
        val plainJavacArgs = buildList {
            addAll(commonJavacArgs)

            add("-classpath")
            add(classpath.joinToString(File.pathSeparator))

            // https://blog.ltgt.net/most-build-tools-misuse-javac/
            // we compile module by module, so we don't need javac lookup into other modules
            add("-sourcepath")
            add(":")
            add("-implicit:none")

            add("-d")
            add(compiledJvmArtifact.javaCompilerOutputRoot.pathString)

            addAll(freeCompilerArgs)

            addAll(javaSourceFiles.map { it.pathString })
        }

        val exitCode = withJavaArgFile(tempRoot, plainJavacArgs) { argsFile ->
            val result = javacSpanBuilder(plainJavacArgs, jdk, incremental = false).use { span ->
                processRunner.runProcess(
                    workingDir = jdk.homeDir,
                    command = [jdk.javacExecutable.pathString, "@${argsFile.pathString}"],
                    span = span,
                    outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger)),
                )
            }
            result.exitCode
        }
        return exitCode == 0
    }

    private fun javacSpanBuilder(args: List<String>, jdk: Jdk, incremental: Boolean): SpanBuilder {
        return spanBuilder("javac")
            .setAttribute("incremental", incremental)
            .setAmperModule(module)
            .setListAttribute("args", args)
            .setAttribute("jdk-home", jdk.homeDir.pathString)
            .setAttribute("version", jdk.version)
    }

    fun shouldCompileJavaIncrementally(javaUserSettings: JavaUserSettings, javaAnnotationProcessorsClassPath: List<Path>): Boolean {
        if (javaAnnotationProcessorsClassPath.isNotEmpty()) {
            // annotation processors are not supported by JPS yet
            return false
        }
        return javaUserSettings.compileIncrementally || System.getProperty("org.jetbrains.amper.jic").toBoolean()
    }

    class Result(
        val classesOutputRoots: List<Path>,
        val module: AmperModule,
        val isTest: Boolean,
        val changes: List<IncrementalCache.Change>,
    ) : TaskResult, ClasspathProvider {
        override val runtimeClasspath: List<Path>
            get() = classesOutputRoots
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}

private fun buildAnnotationProcessorArgs(
    javaSettings: JavaUserSettings,
    processorClasspath: List<Path>,
    generatedSourcesDir: Path,
): List<String> = buildList {
    add("-processorpath")
    add(processorClasspath.joinToString(File.pathSeparator))

    // Add generated sources directory
    add("-s")
    add(generatedSourcesDir.pathString)

    javaSettings.annotationProcessorOptions.forEach { (key, value) ->
        add("-A$key=$value")
    }
}
