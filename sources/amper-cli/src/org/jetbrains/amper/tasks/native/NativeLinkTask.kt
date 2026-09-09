/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.KotlinCompilationType
import org.jetbrains.amper.compilation.KotlinUserSettings
import org.jetbrains.amper.compilation.NativeCompilerCaches
import org.jetbrains.amper.compilation.downloadCompilerPlugins
import org.jetbrains.amper.compilation.downloadNativeCompiler
import org.jetbrains.amper.compilation.kotlinNativeCompilerArgs
import org.jetbrains.amper.compilation.nativeCompilerCachesFor
import org.jetbrains.amper.compilation.optimizationEnabled
import org.jetbrains.amper.compilation.serializableKotlinSettings
import org.jetbrains.amper.compilation.singleLeafFragment
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.frontend.fragmentsTargeting
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.kotlin.native.KonanDistribution
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.stdlib.io.path.cleanDirectoryExcept
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.CinteropKlibsArtifact
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.identificationPhrase
import org.jetbrains.amper.tasks.ios.XcodeBuildSettingsResolution
import org.jetbrains.amper.tasks.ios.productBundleIdentifier
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportParsedLdCallArtifact
import org.jetbrains.amper.tasks.native.swiftpm.parsedLdCallArtifact
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.pathString

internal class NativeLinkTask(
    override val module: AmperModule,
    override val platform: Platform,
    private val userCacheRoot: AmperUserCacheRoot,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    private val tempRoot: AmperProjectTempRoot,
    override val isTest: Boolean,
    override val buildType: BuildType,
    val compilationType: KotlinCompilationType,
    /**
     * The name of the task that produces the klib for the sources of this module.
     */
    val compileKLibTaskId: TaskId,
    /**
     * Task names that produce klibs that need to be exposed as API in the resulting artifact.
     */
    val exportedKLibTaskIds: Set<TaskId>,
    private val kotlinArtifactsDownloader: KotlinArtifactsDownloader =
        KotlinArtifactsDownloader(userCacheRoot, incrementalCache),
    private val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
    private val xcodeBuildSettingsResolution: XcodeBuildSettingsResolution,
): ArtifactTaskBase(), BuildTask {
    init {
        require(platform.isLeaf)
        require(platform.isDescendantOf(Platform.NATIVE))
        require(compilationType != KotlinCompilationType.LIBRARY)
    }

    /**
     * The directory holding the Kotlin/Native per-file incremental caches for this binary.
     * It is reused between task executions.
     *
     * Note: These caches are for the project's own code (unlike the caches of external dependencies, which the compiler
     * shares machine-wide).
     */
    private val nativeIcCacheDir: Path
        get() = taskOutputRoot.path / "kotlin-native-ic-cache"

    private val cinteropKlibs by Selectors.fromModuleWithDependencies(
        type = CinteropKlibsArtifact::class,
        module = module,
        isTest = isTest,
        platform = platform,
        userCacheRoot = userCacheRoot,
        incrementalCache = incrementalCache,
        quantifier = Quantifier.AnyOrNone,
    )

    private val swiftPMImportParsedLdCall: SwiftPMImportParsedLdCallArtifact? by when (compilationType) {
        KotlinCompilationType.BINARY -> parsedLdCallArtifact(
            module = module,
            platform = platform,
        )
        // FIXME: Test that this works
        KotlinCompilationType.LIBRARY,
        KotlinCompilationType.IOS_FRAMEWORK -> ArtifactSelector.never(Quantifier.SingleOrNone)
    }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result {
        val fragments = module.fragments.filter {
            it.platforms.contains(platform) && it.isTest == isTest
        }
        if (fragments.isEmpty()) {
            error("Zero fragments in module ${module.userReadableName} for platform $platform isTest=$isTest")
        }

        val externalKLibs = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .flatMap { it.compileClasspath } // runtime dependencies including transitive
            .distinct()
            .filterKLibs()
            .toList()

        val includeArtifactDependency = dependenciesResult
            .filterIsInstance<NativeCompileKlibTask.Result>()
            .firstOrNull { it.taskId == compileKLibTaskId }
            ?: error("The result of the klib compilation task (${compileKLibTaskId.value}) was not found")
        val includeArtifact = includeArtifactDependency.compiledKlib
        if (includeArtifact == null && isTest) {
            // We may skip linking for test specifically if there's no compiled code in the fragments.
            // Libraries are of no interest here because they can't contain any tests
            logger.debug("No test code was found compiled for ${fragments.identificationPhrase()}, skipping linking")
            return Result(
                linkedBinary = null,
            )
        }

        val compileKLibDependencies = dependenciesResult
            .filterIsInstance<NativeCompileKlibTask.Result>()
            .filter { it.taskId != compileKLibTaskId }

        val exportedKLibDependencies = compileKLibDependencies
            .filter { it.taskId in exportedKLibTaskIds }
        check(exportedKLibDependencies.size == exportedKLibTaskIds.size)

        val compiledKLibs = compileKLibDependencies.mapNotNull { it.compiledKlib } +
                cinteropKlibs.flatMap { it.allKlibs() }
        val exportedKLibs = exportedKLibDependencies.mapNotNull { it.compiledKlib }

        val kotlinUserSettings = fragments.singleLeafFragment().serializableKotlinSettings()

        logger.debug("native link '${module.userReadableName}' -- ${fragments.joinToString(" ") { it.name }}")

        val entryPoints = if (module.type.isApplication()) {
            fragments.mapNotNull { it.settings.native?.entryPoint }.distinct()
        } else emptyList()
        if (entryPoints.size > 1) {
            // TODO raise this error in the frontend?
            userReadableError("Multiple entry points defined in ${fragments.identificationPhrase()}:\n${entryPoints.joinToString("\n")}")
        }
        val entryPoint = entryPoints.singleOrNull()

        val binaryOptions = if (compilationType == KotlinCompilationType.IOS_FRAMEWORK) {
            val appBundleId = xcodeBuildSettingsResolution
                .getResolver(buildType, dependenciesResult).productBundleIdentifier
            // Format framework's bundleId based on app's bundleId
            val frameworkBundleId = "$appBundleId.kotlin.framework"
            logger.debug("Using framework bundleId: `$frameworkBundleId`")
            mapOf("bundleId" to frameworkBundleId)
        } else emptyMap()

        val dependencyCacheRoots =
            if (kotlinUserSettings.nativeCompilerCaches) externalKlibRoots() else []

        val inputFiles = listOfNotNull(includeArtifact, swiftPMImportParsedLdCall?.path) + compiledKLibs
        val artifact = incrementalCache.execute(
            key = taskName.id.value,
            inputValues = mapOf(
                "kotlin.settings" to Json.encodeToString(kotlinUserSettings),
                "entry.point" to (entryPoint ?: ""),
                "task.output.root" to taskOutputRoot.path.pathString,
                "binary.options" to Json.encodeToString(binaryOptions),
                // The contents of the cache directories are *not* inputs of this task: they are
                // compiler-managed state, and a warm cache must not make this task out of date. Only the compiler
                // arguments we derive from them are.
                "native.caches" to Json.encodeToString(
                    dependencyCacheRoots.map { it.pathString } +
                            listOfNotNull(nativeIcCacheDir.pathString.takeIf { kotlinUserSettings.compileIncrementally })
                ),
            ),
            inputFiles = inputFiles,
        ) {
            if (isTest) {
                logger.debug("Linking native test executable for module '${module.userReadableName}' on platform '${platform.pretty}'...")
            } else {
                val binaryKind = when (compilationType) {
                    KotlinCompilationType.IOS_FRAMEWORK -> "framework"
                    else -> "executable"
                }
                if (inputFiles.isEmpty()) {
                    val fragmentsString = module.fragmentsTargeting(platform, isTest = false)
                        .identificationPhrase()
                    userReadableError("Unable to link: there are no inputs (libraries or compiled source code). " +
                            "Ensure that there are sources and/or dependencies for $fragmentsString")
                }
                logger.infoNoConsole("Linking native ${platform.pretty} $binaryKind for module '${module.userReadableName}'...")
            }

            val artifactPath = taskOutputRoot.path.resolve(compilationType.outputFilename(module, platform, isTest))

            val nativeCompiler = downloadNativeCompiler(kotlinUserSettings.compilerVersion, userCacheRoot, jdkProvider)
            val compilerPlugins = kotlinArtifactsDownloader.downloadCompilerPlugins(
                plugins = kotlinUserSettings.compilerPlugins,
                repositories = module.mavenResolveRepositories.map { it.toRepository() },
            )
            val swiftPMLinkerOpts = swiftPMImportParsedLdCall?.parsedLdCall?.ldArgsForExecutable ?: emptyList()

            val nativeCaches = nativeCachesFor(
                konanDistribution = nativeCompiler.konanDistribution,
                kotlinUserSettings = kotlinUserSettings,
                dependencyCacheRoots = dependencyCacheRoots,
            )
            prepareOutputDir(icCacheDir = nativeCaches?.incrementalCacheDir)

            val args = kotlinNativeCompilerArgs(
                buildType = buildType,
                kotlinUserSettings = kotlinUserSettings,
                compilerPlugins = compilerPlugins,
                entryPoint = entryPoint,
                libraryPaths = compiledKLibs + externalKLibs,
                exportedLibraryPaths = exportedKLibs,
                // no need to pass fragments nor sources, we only build from klibs
                fragments = emptyList(),
                fragmentPlatforms = setOf(platform),
                sourceFiles = emptyList(),
                additionalSourceRoots = emptyList(),
                binaryOptions = binaryOptions,
                outputPath = artifactPath,
                compilationType = compilationType,
                include = includeArtifact,
                otherLinkerOpts = swiftPMLinkerOpts,
                nativeCaches = nativeCaches,
            )

            nativeCompiler.compile(processRunner, args, tempRoot, module)

            return@execute IncrementalCache.ExecutionResult([artifactPath])
        }.outputFiles.single()

        return Result(
            linkedBinary = artifact,
        )
    }

    /**
     * The roots under which the klibs of external dependencies can be found, and which are therefore worth letting
     * the compiler cache automatically (`mavenLocal` is not added to the list on purpose).
     */
    private fun externalKlibRoots(): List<Path> = [userCacheRoot.path]

    /**
     * Returns the Kotlin/Native compiler caches to use for this link compilation, or null if caches cannot or should
     * not be used.
     * The [konanDistribution] is the one of the compiler that will run,
     * and [dependencyCacheRoots] are the roots to cache external dependencies from (empty if dependency caching is disabled).
     */
    private fun nativeCachesFor(
        konanDistribution: KonanDistribution,
        kotlinUserSettings: KotlinUserSettings,
        dependencyCacheRoots: List<Path>,
    ): NativeCompilerCaches? = nativeCompilerCachesFor(
        konanDistribution = konanDistribution,
        target = platform,
        compilationType = compilationType,
        optimizationEnabled = kotlinUserSettings.optimizationEnabled(buildType),
        dependencyCacheRoots = dependencyCacheRoots,
        emptyAutoCacheRoot = createEmptyAutoCacheRoot(),
        // Incremental compilation might work without caching the external dependencies.
        // Unlike in KGP, incremental compilation is switched ON by default for Kotlin >= 2.4.0,
        // see [KotlinSettings.compileIncrementally]
        compileIncrementally = kotlinUserSettings.compileIncrementally,
        // The directory is managed by the compiler and is not a part of the incremental cache inputs
        incrementalCacheDir = nativeIcCacheDir,
    )

    /**
     * Creates and returns a directory that is guaranteed to contain no klibs.
     *
     * The compiler requires auto-cache roots to exist, and this one must stay empty: it is only ever passed to
     * enable the caches prebuilt in the Kotlin/Native distribution, without making any klib eligible for caching
     * (see the KT-88316 workaround in [nativeCompilerCachesFor]).
     */
    private fun createEmptyAutoCacheRoot(): Path =
        tempRoot.path.resolve("empty-native-auto-cache-root").createDirectories()

    /**
     * Cleans the outputs of the previous run, keeping the given [icCacheDir] (if any) so the compiler can update the
     * incremental caches instead of rebuilding them from scratch. The [icCacheDir] is also created if missing,
     * because the compiler requires it to exist.
     *
     * When incremental compilation is not used in this run, the caches are wiped along with the rest of the outputs,
     * so that enabling it again later starts from a clean state.
     */
    private fun prepareOutputDir(icCacheDir: Path?) {
        if (icCacheDir == null) {
            taskOutputRoot.path.clean()
        } else {
            cleanDirectoryExcept(taskOutputRoot.path, keepPaths = [icCacheDir])
            icCacheDir.createDirectories()
        }
    }

    class Result(
        val linkedBinary: Path?,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)
}
