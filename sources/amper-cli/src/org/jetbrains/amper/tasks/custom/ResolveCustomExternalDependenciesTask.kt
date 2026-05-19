/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.custom

import com.intellij.openapi.vfs.VirtualFile
import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.CliReportingMavenResolver
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.AmperModuleFileSource
import org.jetbrains.amper.frontend.Artifact
import org.jetbrains.amper.frontend.ClassBasedSet
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentLink
import org.jetbrains.amper.frontend.Layout
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.MavenCoordinates
import org.jetbrains.amper.frontend.MavenDependency
import org.jetbrains.amper.frontend.ModulePart
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.VersionCatalog
import org.jetbrains.amper.frontend.aomBuilder.DefaultLocalModuleDependency
import org.jetbrains.amper.frontend.api.DefaultTrace
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.frontend.dr.resolver.ModuleResolutionFilter
import org.jetbrains.amper.frontend.dr.resolver.ResolutionType
import org.jetbrains.amper.frontend.dr.resolver.getExternalDependencies
import org.jetbrains.amper.frontend.dr.resolver.toDrMavenCoordinates
import org.jetbrains.amper.frontend.mavenResolveRepositories
import org.jetbrains.amper.frontend.plugins.AmperMavenPluginDescription
import org.jetbrains.amper.frontend.plugins.TaskFromPluginDescription
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.schema.Settings
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.toIncrementalCacheResult
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.pathString

internal class ResolveCustomExternalDependenciesTask(
    override val taskName: TaskName,
    private val userCacheRoot: AmperUserCacheRoot,
    private val module: AmperModule,
    private val incrementalCache: IncrementalCache,
    private val resolutionScope: ResolutionScope,
    private val externalDependencies: List<MavenCoordinates>,
    private val localDependencies: List<AmperModule>,
) : Task {
    /**
     * The task supports JVM only resolution.
     * This limitation comes from the plugins subsystem,
     * technically there is no problem to support multi-platform resolution from the DR perspective here.
     */
    private val platform = ResolutionPlatform.JVM
    private val isTest = false
    private val resolutionType = ResolutionType.MAIN

    private val mavenResolver = CliReportingMavenResolver(userCacheRoot, incrementalCache)

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext,
    ): Result {
        val dependencyPaths: List<Path> =
            when {
                externalDependencies.isEmpty() && localDependencies.isEmpty() -> emptyList()
                // todo (AB) : Inside this task
                //  the case for COMPILE scope (resolution of module compilation classpath) is undistinguishable
                //  from the case where user would like to declare compile classpath that depends on
                //  module code and on its exported dependencies only
                //  (like a COMPILE classpath of the module that depends on the declared one).
                //  The latter case is imagined one so we don't support it for now assuming that a single dependency on
                //  the local module means requesting classpath of that module.
                //  See https://youtrack.jetbrains.com/issue/AMPER-5243 for details
                externalDependencies.isEmpty() && localDependencies.size == 1 -> {
                    // Resolve local module dependencies
                    localDependencies.single().resolveModuleDependencies()
                }
                localDependencies.isEmpty() -> {
                    // Resolve module-agnostic list of Maven dependencies
                    resolveExternalMavenDependencies(externalDependencies)
                }
                else -> {
                    // Mixed external and module dependencies
                    if (resolutionScope == ResolutionScope.COMPILE) {
                        throw UnsupportedOperationException(
                            "Mixed external and module dependencies are not supported for COMPILE resolution scope yet."
                        )
                    }
                    resolveMixedExternalAndModuleDependencies()
                }
            }

        return Result(
            resolvedFiles = dependencyPaths,
        )
    }

    private suspend fun AmperModule.resolveModuleDependencies(): List<Path> {
        val moduleDependencies = with(ModuleDependencies) {
            val resolutionSettings = AmperResolutionSettings(userCacheRoot, incrementalCache, GlobalOpenTelemetry.get())
            moduleDependencies(resolutionSettings)
        }

        val result = ResolveExternalDependenciesTask.resolveModuleDependencies(
            resolutionPlatform = platform,
            userCacheRoot = userCacheRoot,
            incrementalCache = incrementalCache,
            isTest = isTest,
            moduleDependencies = moduleDependencies,
            mavenResolver = mavenResolver,
        )

        return when (resolutionScope) {
            ResolutionScope.COMPILE -> result.compileClasspath
            ResolutionScope.RUNTIME -> result.runtimeClasspath
        }
    }

    private suspend fun resolveExternalMavenDependencies(externalDependencies: List<MavenCoordinates>): List<Path> {
        val repositories = module.mavenResolveRepositories.map { it.toRepository() }

        val dependencyPaths = incrementalCache.execute(
            key = taskName.name,
            inputValues = mapOf(
                "userCacheRoot" to userCacheRoot.path.pathString,
                "repositories" to repositories.joinToString("|"),
                "resolveScope" to resolutionScope.name,
                "dependencies" to externalDependencies.joinToString("|"),
            ),
            inputFiles = emptyList()
        ) {
            spanBuilder(taskName.name)
                .setAmperModule(module)
                .setListAttribute("dependencies-coordinates", externalDependencies.map { it.toString() })
                .use {
                    val resolvedGraph = mavenResolver.resolve(
                        coordinates = externalDependencies.map { it.toDrMavenCoordinates() },
                        repositories = repositories,
                        scope = resolutionScope,
                        platform = platform,
                        resolveSourceMoniker = "custom external dependencies"
                    )
                    resolvedGraph.toIncrementalCacheResult()
                }
        }.outputFiles

        return dependencyPaths
    }

    /**
     * Resolves mixes external and module dependencies.
     * Semantically, it is equivalent to declaring a module with given mixed dependencies and then resolving
     * dependencies of that module according to Amper module resolution rules.
     * Note:
     * - Non-exported compile dependencies of given local modules are not included in the resolution result for COMPILE
     *   resolution scope.
     * - Still, versions of libraries from COMPILE and RUNTIME classpath of given mixed dependencies are aligned.
     */
    private suspend fun resolveMixedExternalAndModuleDependencies(): List<Path> {
        val module = getModuleForMixedDeps()
        val moduleDependencies = with(ModuleDependencies) {
            val resolutionSettings = AmperResolutionSettings(userCacheRoot, incrementalCache, GlobalOpenTelemetry.get())
            module.moduleDependencies(resolutionSettings)
        }

        val moduleDependenciesRoot = moduleDependencies.allLeafPlatformsGraph(isForTests = false)
        val externalDependencies = moduleDependenciesRoot.children.flatMap { it.getExternalDependencies() }

        val repositories = module.mavenResolveRepositories.map { it.toRepository() }

        val dependencyPaths = incrementalCache.execute(
            key = taskName.name,
            inputValues = mapOf(
                "userCacheRoot" to userCacheRoot.path.pathString,
                "repositories" to repositories.joinToString("|"),
                "resolutionScope" to resolutionScope.name,
                "dependencies" to externalDependencies.joinToString("|"),
            ),
            inputFiles = emptyList()
        ) {
            spanBuilder(taskName.name)
                .setAmperModule(module)
                .setListAttribute("dependencies-coordinates", externalDependencies.map { it.toString() })
                .use {
                    val resolvedGraph = with(ModuleDependencies) {
                        resolveModuleDependencies(
                            moduleDependenciesList = listOf(moduleDependencies),
                            leafPlatformsOnly = true,
                            filter = ModuleResolutionFilter(resolutionScope, platforms = setOf(platform), resolutionType = resolutionType),
                        )
                    }

                    resolvedGraph.toIncrementalCacheResult()
                }
        }.outputFiles

        return dependencyPaths
    }

    /**
     * Creates a synthetic module that combines given dependencies for custom resolution.
     */
    private fun getModuleForMixedDeps(): AmperModule {
        val requestedLocalModuleDependencies = localDependencies
        val requestedExternalDependencies = externalDependencies

        val moduleName = "${module.userReadableName}:$taskName:classpath"
        val hostModule = module

        val syntheticModule = object : AmperModule by module {
            override val userReadableName: String = moduleName
            override val type: ProductType = hostModule.type
            override val source: AmperModuleFileSource =
                // Unique module source
                AmperModuleFileSource(
                    hostModule.source.moduleDir.resolve(taskName.name.replace(Regex("[^a-zA-Z0-9.\\-_]"), ""))
                )
            override val aliases: Map<String, Set<Platform>> = emptyMap()
            override val fragments: MutableList<Fragment> = mutableListOf()
            override val artifacts: List<Artifact> = emptyList()
            override val parts: ClassBasedSet<ModulePart<*>> = hostModule.parts
            override val usedCatalog: VersionCatalog = hostModule.usedCatalog
            override val usedTemplates: List<VirtualFile> = emptyList()
            override val tasksFromPlugins: List<TaskFromPluginDescription> = emptyList()
            override val layout: Layout = hostModule.layout
            override val amperMavenPluginsDescriptions: List<AmperMavenPluginDescription> = emptyList()
            override val commonModuleNode get() = throw UnsupportedOperationException("Synthetic module")
        }

        val fragment = object : LeafFragment {
            override val platform: Platform = Platform.JVM
            override val platforms: Set<Platform> = setOf(Platform.JVM)
            override val isTest: Boolean = false
            override val name: String = "$moduleName:fragment"
            override val modifier: String = ""
            override val module: AmperModule = syntheticModule
            override val externalDependencies: List<Notation> = buildList {
                requestedLocalModuleDependencies.forEach {
                    add(DefaultLocalModuleDependency(it, Path("."), DefaultTrace))
                }
                requestedExternalDependencies.forEach {
                    add(MavenDependency(it, it.trace))
                }
            }
            override val fragmentDependencies: List<FragmentLink> = emptyList()
            override val fragmentDependants: List<FragmentLink> = emptyList()
            override val sourceRoots: List<Path> = emptyList()
            override val resourcesPath: Path = Path(".")
            override val composeResourcesPath: Path = Path(".")
            override val hasAnyComposeResources: Boolean = false
            override val cinteropPath: Path? get() = null

            override val settings: Settings =
                hostModule.fragments.firstOrNull { it.platforms.singleOrNull() == Platform.JVM }?.settings ?: Settings()

            override fun generatedSourceDirs(buildOutputRoot: Path): List<Path> = emptyList()
            override fun generatedResourceDirs(buildOutputRoot: Path): List<Path> = emptyList()
            override fun generatedClassDirs(buildOutputRoot: Path): List<Path> = emptyList()
        }

        syntheticModule.fragments.add(fragment)

        return syntheticModule
    }

    class Result(
        val resolvedFiles: List<Path>,
    ) : TaskResult
}