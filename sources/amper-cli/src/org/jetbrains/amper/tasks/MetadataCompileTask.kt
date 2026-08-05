/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import kotlinx.serialization.json.Json
import org.jetbrains.amper.CliReportingMavenResolver
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.KotlinCompilationType
import org.jetbrains.amper.compilation.KotlinUserSettings
import org.jetbrains.amper.compilation.compiler.downloadKotlinCompiler
import org.jetbrains.amper.compilation.downloadCompilerPlugins
import org.jetbrains.amper.compilation.downloadNativeCompiler
import org.jetbrains.amper.compilation.kotlinMetadataCompilerArgs
import org.jetbrains.amper.compilation.kotlinModuleName
import org.jetbrains.amper.compilation.kotlinNativeCompilerArgs
import org.jetbrains.amper.compilation.serializableKotlinSettings
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.allFragmentDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.flow.toPlatform
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.friends
import org.jetbrains.amper.frontend.mavenResolveRepositories
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jvm.getJdkOrUserError
import org.jetbrains.amper.kotlin.native.KonanDistribution
import org.jetbrains.amper.kotlin.native.asCommonizerTarget
import org.jetbrains.amper.kotlin.native.librariesForMetadataCompilation
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.stdlib.io.path.clean
import org.jetbrains.amper.stdlib.io.path.isEmptyDirectory
import org.jetbrains.amper.stdlib.io.path.listDirectoryEntriesIfExistsOrEmpty
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.KotlinJavaSourceDirArtifact
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.metadata.sourceSetName
import org.jetbrains.amper.tasks.native.CommonizeCInteropKlibsTask
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.walk
import kotlin.io.path.writeText

internal class MetadataCompileTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    private val moduleDependencies: ModuleDependencies,
    private val fragment: Fragment,
    private val userCacheRoot: AmperUserCacheRoot,
    private val tempRoot: AmperProjectTempRoot,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    private val kotlinArtifactsDownloader: KotlinArtifactsDownloader =
        KotlinArtifactsDownloader(userCacheRoot, incrementalCache),
    private val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
): ArtifactTaskBase(), BuildTask {

    override val buildType: BuildType get() = BuildType.Debug
    override val platform: Platform = Platform.COMMON
    override val isTest: Boolean = fragment.isTest

    private val additionalKotlinJavaSourceDirs by Selectors.fromFragment(
        type = KotlinJavaSourceDirArtifact::class,
        fragment = fragment,
        quantifier = Quantifier.AnyOrNone,
    )

    private val mavenResolver by lazy {
        CliReportingMavenResolver(userCacheRoot, incrementalCache)
    }

    /**
     * Metadata compilation of shared fragment accepts the following input:
     * - fragment sources (cinterop sources are not included)
     * - fragment external compile dependencies
     *   -- all suitable source sets from a dependency's common KLib, including commonized cinterop source sets
     *   -- compile dependencies of a shared fragment that targets native platforms ONLY, include transitive local module dependencies as well.
     * - metadata compilation result of all same-module fragments refining this one (dependsOn closure)
     * - commonized cinterop sources of all target platforms
     */
    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): Result {
        checkFragment()

        val kotlinSettings = fragment.serializableKotlinSettings()
        val fragmentPlatforms = fragment.platforms.mapNotNull { it.toResolutionPlatform() }.toSet()

        // todo (AB) : [AMPER-721] Wrap the classpath into incremental cache to avoid deserializing module-graph.
        val resolvedGraph = mavenResolver.resolve(moduleDependencies = moduleDependencies, isTest = isTest, leafPlatformsOnly = false)
        val mavenClasspath = resolvedGraph.root.children.filter {
            it is ModuleDependencyNode
                    && it.isForTests == isTest
                    && it.resolutionConfig.platforms == fragmentPlatforms
                    && it.resolutionConfig.scope == ResolutionScope.COMPILE
        }.let {
            it.singleOrNull() as? ModuleDependencyNode
                ?: error("Expected single ${ModuleDependencyNode::class.simpleName}, found ${it.size}")
        }

        val localDependencies = dependenciesResult.filterIsInstance<Result>()

        // includes this module's fragment dependencies and other source fragment deps from other local modules
        val localClasspath = localDependencies.mapNotNull {
            it.metadataOutputRoot.takeIf { !it.isEmptyDirectory() }
        }

        val cinteropDependencies = dependenciesResult.filterIsInstance<CommonizeCInteropKlibsTask.Result>()
            .mapNotNull {
                val commonizedCInterops = it.path.listDirectoryEntriesIfExistsOrEmpty().associateBy { it.fileName.toString() }
                val commonizerTarget = fragment.platforms.asCommonizerTarget()
                val commonizedCinteropsDir = commonizedCInterops[commonizerTarget.dirName]
                commonizedCinteropsDir?.listDirectoryEntries()
            }.flatten()

        val fragmentClasspath = localClasspath + cinteropDependencies + mavenClasspath.dependencyPaths()

        val refinesPaths = fragment
            .allFragmentDependencies(dependencyType = FragmentDependencyType.REFINE)
            .map { localDependencies.findMetadataResultForFragment(it).metadataOutputRoot }
            .toList()

        // todo (AB) : [AMPER-721] This is a closed party, friends are not invited I am afraid.
        //  Test fragments are also not invited, neither are single-platform fragments (check if friends parameter is needed here)
        val friendPaths =
            fragment.friends.map { localDependencies.findMetadataResultForFragment(it).metadataOutputRoot }

        val jdk = jdkProvider.getJdkOrUserError(jdkSettings = fragment.settings.jvm.jdk)

        return if (fragmentPlatforms.all { it.nativeTarget != null })   {
            compileNativeMetadata(fragmentClasspath, refinesPaths, friendPaths, kotlinSettings, fragmentPlatforms, jdk)
        } else {
            compileCommonMetadata(fragmentClasspath, refinesPaths, friendPaths, kotlinSettings, fragmentPlatforms, jdk)
        }
    }

    context(_: ProblemReporter)
    private suspend fun compileCommonMetadata(
        fragmentClasspath: List<Path>,
        refinesPaths: List<Path>,
        friendPaths: List<Path>,
        kotlinSettings: KotlinUserSettings,
        fragmentPlatforms: Set<ResolutionPlatform>,
        jdk: Jdk,
    ): Result {
        val inputValues = mapOf(
            "jdk.version" to jdk.version,
            "jdk.home" to jdk.homeDir.pathString,
            "user.settings" to Json.encodeToString(kotlinSettings),
            "task.output.root" to taskOutputRoot.path.pathString,
        )

        val sourceDirs =
            fragment.sourceRoots.map { it.toAbsolutePath() } + additionalKotlinJavaSourceDirs.map { it.path }
        val inputFiles = sourceDirs + fragmentClasspath + refinesPaths + friendPaths

        incrementalCache.execute(taskName.id.value, inputValues, inputFiles) {
            taskOutputRoot.path.clean()

            val existingSourceDirs = sourceDirs.filter { it.exists() }
            if (existingSourceDirs.isNotEmpty()) {
                existingSourceDirs.forEach {
                    if (!it.isDirectory()) {
                        userReadableError("Source directory '$it' exists, but it's not a directory, this is currently unsupported")
                    }
                }
                compileCommonSources(
                    jdk = jdk,
                    kotlinUserSettings = kotlinSettings,
                    sourceDirectories = sourceDirs,
                    additionalSourceRoots = additionalKotlinJavaSourceDirs.map { SourceRoot(it.fragmentName, it.path) },
                    classpath = fragmentClasspath,
                    friendPaths = friendPaths,
                    refinesPaths = refinesPaths,
                    fragmentPlatforms = fragmentPlatforms,
                )
            } else {
                logger.debug("No sources were found for ${fragment.identificationPhrase()}, skipping compilation")
            }

            return@execute IncrementalCache.ExecutionResult(listOf(taskOutputRoot.path.toAbsolutePath()))
        }

        return Result(
            metadataOutputRoot = taskOutputRoot.path.toAbsolutePath(),
            module = module,
            fragment = fragment,
        )
    }

    private fun List<Result>.findMetadataResultForFragment(f: Fragment) =
        // can't use identity check because some fragments are wrapped, and [equals] is not overridden
        firstOrNull { it.module.userReadableName == f.module.userReadableName && it.fragment.name == f.name }
            ?: error("Metadata compilation result not found for dependency fragment ${f.module.userReadableName}:" +
                    "${f.name} of this fragment ${module.userReadableName}:${fragment.name}. Actual results: " +
                    map { "${it.module.userReadableName}:${it.fragment.name}" })

    context(_: ProblemReporter)
    private suspend fun compileNativeSharedSources(
        kotlinUserSettings: KotlinUserSettings,
        sourceDirectories: List<Path>,
        additionalSourceRoots: List<SourceRoot>,
        classpath: List<Path>,
        friendPaths: List<Path>,
        refinesPaths: List<Path>,
        fragmentPlatforms: Set<ResolutionPlatform>,
        jdk: Jdk,
    ) {
        // In Kotlin >= 2.2, we need to list all source files (not just dirs), and konanc only accepts *.kt entries.
        // Any other file lying around in the source directories (.DS_Store, .gitkeep, notes...) would be rejected
        // with "source entry is not a Kotlin file", so we must filter them out.
        val kotlinSourceFiles = sourceDirectories.flatMap { it.walk() }.filter { it.extension == "kt" }.toList()
        if (kotlinSourceFiles.isEmpty()) {
            return
        }

        val nativeCompiler = downloadNativeCompiler(kotlinUserSettings.compilerVersion, userCacheRoot, jdkProvider)
        val commonizedKlibs = nativeCompiler.konanDistribution.librariesForMetadataCompilation(fragmentPlatforms)

        val compilerPlugins = kotlinArtifactsDownloader.downloadCompilerPlugins(
            plugins = kotlinUserSettings.compilerPlugins,
            repositories = module.mavenResolveRepositories.map { it.toRepository() },
        )

        // This manifest file clears 'native_targets' in the resulting klib manifest (see kotlinNativeCompilerArgs).
        // It must not live under taskOutputRoot: with '-nopack', that directory *is* the resulting klib.
        val manifest = tempRoot.path.createDirectories()
            .resolve("${module.userReadableName}-${fragment.name}-inputManifest")
            .also { it.writeText("native_targets=\n") }

        val compilerArgs = kotlinNativeCompilerArgs(
            buildType = buildType,
            kotlinUserSettings = kotlinUserSettings,
            compilerPlugins = compilerPlugins,
            entryPoint = null,
            libraryPaths = commonizedKlibs + classpath,
            exportedLibraryPaths = [],
            fragments = [fragment],
            sourceFiles = kotlinSourceFiles,
            additionalSourceRoots = additionalSourceRoots,
            binaryOptions = emptyMap(),
            outputPath = taskOutputRoot.path,
            compilationType = KotlinCompilationType.LIBRARY,
            include = null,
            fragmentPlatforms = fragmentPlatforms.map { it.toPlatform() }.toSet(),
            refinesPaths = refinesPaths,
            metadataManifestFile = manifest,
        )

        spanBuilder("kotlin-native-metadata-compilation")
            .setAmperModule(module)
            .setListAttribute("source-dirs", sourceDirectories.map { it.pathString })
            .setAttribute("compiler-version", kotlinUserSettings.compilerVersion)
            .setListAttribute("compiler-args", compilerArgs)
            .use {
                logger.infoNoConsole("Compiling Native Kotlin metadata for '${fragment.identificationPhrase()})'...")
                nativeCompiler.compile(processRunner, compilerArgs, tempRoot, module)
            }
    }

    private fun KonanDistribution.librariesForMetadataCompilation(
        fragmentPlatforms: Set<ResolutionPlatform>,
    ): List<Path> = librariesForMetadataCompilation(fragmentPlatforms.map { it.toPlatform() }.asCommonizerTarget())

    context(_: ProblemReporter)
    private suspend fun compileCommonSources(
        jdk: Jdk,
        kotlinUserSettings: KotlinUserSettings,
        sourceDirectories: List<Path>,
        additionalSourceRoots: List<SourceRoot>,
        classpath: List<Path>,
        friendPaths: List<Path>,
        refinesPaths: List<Path>,
        fragmentPlatforms: Set<ResolutionPlatform>,
    ) {
        // In Kotlin >= 2.2, we need to list all source files (not just dirs), and the metadata compiler only accepts
        // *.kt entries. Any other file lying around in the source directories (.DS_Store, .gitkeep, notes...) would be
        // rejected with "source entry is not a Kotlin file", so we must filter them out.
        val kotlinSourceFiles = sourceDirectories.flatMap { it.walk() }.filter { it.extension == "kt" }.toList()
        if (kotlinSourceFiles.isEmpty()) {
            return
        }

        val nativeCompiler = downloadNativeCompiler(kotlinUserSettings.compilerVersion, userCacheRoot, jdkProvider)
        val commonizedKlibs = nativeCompiler.konanDistribution.librariesForMetadataCompilation(fragmentPlatforms)

        val kotlinCompiler = kotlinArtifactsDownloader.downloadKotlinCompiler(kotlinUserSettings.compilerVersion, jdk)
        val compilerPlugins = kotlinArtifactsDownloader.downloadCompilerPlugins(
            plugins = kotlinUserSettings.compilerPlugins,
            repositories = module.mavenResolveRepositories.map { it.toRepository() },
        )
        val compilerArgs = kotlinMetadataCompilerArgs(
            kotlinUserSettings = kotlinUserSettings,
            moduleName = "${module.kotlinModuleName(isTest)}_${fragment.sourceSetName()}",
            classpath = commonizedKlibs + classpath,
            compilerPlugins = compilerPlugins,
            outputPath = taskOutputRoot.path,
            friendPaths = friendPaths,
            refinesPaths = refinesPaths,
            fragments = listOf(fragment),
            sourceFiles = kotlinSourceFiles,
            additionalSourceRoots = additionalSourceRoots,
            fragmentPlatforms = fragmentPlatforms,
        )
        spanBuilder("kotlin-common-metadata-compilation")
            .setAmperModule(module)
            .setListAttribute("source-dirs", sourceDirectories.map { it.pathString })
            .setAttribute("compiler-version", kotlinUserSettings.compilerVersion)
            .setListAttribute("compiler-args", compilerArgs)
            .use {
                logger.infoNoConsole("Compiling Kotlin metadata for module '${module.userReadableName}'...")
                val result = context(processRunner) {
                    kotlinCompiler.compileMetadata(
                        compilerArgs = compilerArgs,
                        argsMode = ArgsMode.ArgFile(tempRoot = tempRoot),
                    )
                }
                if (result.exitCode != 0) {
                    userReadableError("Kotlin metadata compilation failed (see errors above)")
                }
            }
    }

    context(_: ProblemReporter)
    private suspend fun compileNativeMetadata(
        fragmentClasspath: List<Path>,
        refinesPaths: List<Path>,
        friendPaths: List<Path>,
        kotlinSettings: KotlinUserSettings,
        fragmentPlatforms: Set<ResolutionPlatform>,
        jdk: Jdk,
    ): Result {
        val inputValues = mapOf(
            "jdk.version" to jdk.version,
            "jdk.home" to jdk.homeDir.pathString,
            "user.settings" to Json.encodeToString(kotlinSettings),
            "task.output.root" to taskOutputRoot.path.pathString,
        )

        val sourceDirs =
            fragment.sourceRoots.map { it.toAbsolutePath() } + additionalKotlinJavaSourceDirs.map { it.path }
        val inputFiles = sourceDirs + fragmentClasspath + refinesPaths + friendPaths

        incrementalCache.execute(taskName.id.value, inputValues, inputFiles) {
            taskOutputRoot.path.clean()

            val existingSourceDirs = sourceDirs.filter { it.exists() }
            if (existingSourceDirs.isNotEmpty()) {
                existingSourceDirs.forEach {
                    if (!it.isDirectory()) {
                        userReadableError("Source directory '$it' exists, but it's not a directory, this is currently unsupported")
                    }
                }
                compileNativeSharedSources(
                    kotlinUserSettings = kotlinSettings,
                    sourceDirectories = sourceDirs,
                    additionalSourceRoots = additionalKotlinJavaSourceDirs.map { SourceRoot(it.fragmentName, it.path) },
                    classpath = fragmentClasspath,
                    friendPaths = friendPaths,
                    refinesPaths = refinesPaths,
                    fragmentPlatforms = fragmentPlatforms,
                    jdk = jdk,
                )
            } else {
                logger.debug("No sources were found for ${fragment.identificationPhrase()}, skipping compilation")
            }

            return@execute IncrementalCache.ExecutionResult(listOf(taskOutputRoot.path.toAbsolutePath()))
        }

        return Result(
            metadataOutputRoot = taskOutputRoot.path.toAbsolutePath(),
            module = module,
            fragment = fragment,
        )
    }

    private fun checkFragment() {
        check(!fragment.isTest) { "Metadata compilation is not supported for test fragments: ${fragment.module.userReadableName}#${fragment.name}" }
        check (fragment.platforms.size > 1) { "Metadata compilation is not supported for single-platform fragments: ${fragment.module.userReadableName}#${fragment.name}" }
    }

    class Result(
        val metadataOutputRoot: Path,
        val module: AmperModule,
        val fragment: Fragment,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)
}
