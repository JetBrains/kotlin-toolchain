/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.dependency.resolution.Cache
import org.jetbrains.amper.dependency.resolution.CacheEntryKey
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyGraph
import org.jetbrains.amper.dependency.resolution.DependencyGraph.Companion.toGraph
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.DependencyNodeHolderWithContext
import org.jetbrains.amper.dependency.resolution.FileCacheBuilder
import org.jetbrains.amper.dependency.resolution.GraphJson
import org.jetbrains.amper.dependency.resolution.IncrementalCacheUsage
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenLocal
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.amper.dependency.resolution.Repository
import org.jetbrains.amper.dependency.resolution.ResolutionLevel
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.ResolvedGraph
import org.jetbrains.amper.dependency.resolution.Resolver
import org.jetbrains.amper.dependency.resolution.RootDependencyNode
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeStub
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeWithContext
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNode
import org.jetbrains.amper.dependency.resolution.SerializableRootDependencyNode
import org.jetbrains.amper.dependency.resolution.asRootCacheEntryKey
import org.jetbrains.amper.dependency.resolution.getDependenciesGraphInput
import org.jetbrains.amper.dependency.resolution.infoSpanBuilder
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RepositoriesModulePart
import org.jetbrains.amper.frontend.allFragmentDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.resolveProjectDependencies
import org.jetbrains.amper.frontend.dr.resolver.flow.Classpath
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.fragmentsToDependOnFromOtherModuleFragmentWith
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.schema.Repository.Companion.SpecialMavenLocalUrl
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.ResultWithSerializable
import org.jetbrains.amper.incrementalcache.execute
import org.jetbrains.amper.mavencentral.MavenCentralDefaultConfiguration
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString

private val logger = LoggerFactory.getLogger(ModuleDependencies::class.java)

/**
 * Provides dependencies graphs for all module fragments and for leaf platforms.
 *
 * Graphs are built based on AOM and are unresolved.
 * (i.e., only effective direct dependencies of modules are included,
 *  external transitive dependencies are not resolved and are absent in the resulting graphs,
 *  constructing unresolved graphs is done without NETWORK access)
 */
class ModuleDependencies private constructor(
    val module: AmperModule,
    private val resolutionSettings: AmperResolutionSettings,
    private val sharedResolutionCache: Cache,
) {
    private val mainDepsPerFragment: Map<Fragment, PerFragmentDependencies> =
        module.perFragmentDependencies(false)

    private val testDepsPerFragment: Map<Fragment, PerFragmentDependencies> =
        module.perFragmentDependencies(true)

    private val mainDepsPerPlatforms: Map<Set<Platform>, PerFragmentDependencies>
    private val testDepsPerPlatforms: Map<Set<Platform>, PerFragmentDependencies>

    private val mainDepsPerLeafPlatform: Map<Platform, PerFragmentDependencies>
    private val testDepsPerLeafPlatform: Map<Platform, PerFragmentDependencies>

    init {
        mainDepsPerPlatforms = perPlatformDependencies(false)
        testDepsPerPlatforms = perPlatformDependencies(true)

        mainDepsPerLeafPlatform = mainDepsPerPlatforms.mapNotNull { [platforms, deps] -> platforms.singleOrNull()?.let { it to deps} }.toMap()
        testDepsPerLeafPlatform = testDepsPerPlatforms.mapNotNull { [platforms, deps] -> platforms.singleOrNull()?.let { it to deps} }.toMap()
    }

    private fun perPlatformDependencies(isTest: Boolean): Map<Set<Platform>, PerFragmentDependencies> =
        buildMap {
            val depsPerFragment = if (isTest) testDepsPerFragment else mainDepsPerFragment
            depsPerFragment.forEach { [fragment, dependencies] ->
                if (fragment.fragmentDependants.none { it.target.isTest == isTest && it.target.platforms == fragment.platforms }) {
                    // most specific fragment corresponding to the platforms set
                    put(fragment.platforms, dependencies)
                }
            }
        }

    private fun AmperModule.perFragmentDependencies(isTest: Boolean): Map<Fragment, PerFragmentDependencies> =
        fragments
            .filter { it.isTest == isTest }
            .sortedBy { it.name }
            .associateBy(keySelector = { it }) {
                PerFragmentDependencies(it, resolutionSettings, sharedResolutionCache)
            }

    /**
     * This method returns an unresolved graph of module dependencies for all leaf platforms.
     * This exact graph is used as an input for module dependencies resolution
     * performed by the method [ModuleDependencies.resolveModuleDependencies] with parameter
     * [leafPlatformsOnly] set to [true].
     *
     * This resolution mode (resolving leaf-platform's dependencies) is used in the CLI for now,
     * since there is no need to resolve dependencies of non-leaf fragments (multiplatform ones).
     * Dependencies' versions are aligned across all module leaf-fragments of the same type (main/test).
     *
     * @return the root umbrella node with the list of module nodes of type [ModuleDependencyNode] (one module node per platform).
     * I.e., Each node from the list contains
     * unresolved platform-specific module dependencies corresponding to one of the module leaf platforms.
     */
    fun allLeafPlatformsGraph(isForTests: Boolean): RootDependencyNodeWithContext {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isForTests) testDepsPerLeafPlatform else mainDepsPerLeafPlatform

        val leafPlatformDependencies = buildList {
            perPlatformDeps.values.forEach {
                add(it.compileDeps)
                it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps) }
            }
        }

        return RootDependencyNodeWithContext(
            // If the incremental cache is on, a separate cache entry is calculated and maintained for every unique combination of parameters:
            //  - module
            //  - leaf-platforms/all-platforms mode
            //  - isTest flag
            rootCacheEntryKey = CacheEntryKey.CompositeCacheEntryKey(listOfNotNull(
                module.uniqueModuleKey(),
                "leaf platforms",
                isForTests
            )).asRootCacheEntryKey(),
            children = leafPlatformDependencies,
            templateContext = resolutionSettings.toEmptyContext()
        )
    }

    /**
     * @return unresolved compile/runtime module dependencies for the particular leaf module platform.
     */
    fun forPlatform(platform: Platform, isTest: Boolean): PerFragmentDependencies {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isTest) testDepsPerLeafPlatform else mainDepsPerLeafPlatform
        return perPlatformDeps[platform]
            ?: error("Dependencies for $platform are not calculated")
    }

    /**
     * @return unresolved compile/runtime module dependencies for the particular leaf module platform.
     */
    private fun forPlatforms(platforms: Set<Platform>, isTest: Boolean): PerFragmentDependencies {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformDeps = if (isTest) testDepsPerPlatforms else mainDepsPerPlatforms
        return perPlatformDeps[platforms]
            ?: error("Dependencies for $platforms are not calculated")
    }

    /**
     * This method returns an unresolved dependencies graph for all module fragments
     * with the unique list of platforms.
     * This exact graph is used as an input for module dependencies resolution
     * performed by the method [ModuleDependencies.resolveModuleDependencies] with parameter
     * [leafPlatformsOnly] set to [false].
     *
     * This resolution mode (resolving all fragments' dependencies) is used in Idea Plugin for Ide Sync,
     * aligning library versions across all module fragments of the same type (main/test).
     * After the resolution is finished, IDe converts every fragment into a separate module in the Workspace model
     * with the list of resolved fragment dependencies
     *
     * @return the root umbrella node with the list of module nodes of type [ModuleDependencyNode] (one module node per fragment).
     * I.e., Each node from the list contains
     * unresolved module dependencies corresponding to one of the module fragments.
     */
    internal fun allFragmentsGraph(isForTests: Boolean, flattenGraph: Boolean): DependencyNodeHolderWithContext {
        // Test dependencies contain dependencies of both test and corresponding main fragments
        val perPlatformsDeps = if (isForTests) testDepsPerPlatforms else mainDepsPerPlatforms

        return if (!flattenGraph) {
            val fragmentDependencies = buildList {
                perPlatformsDeps.values.forEach {
                    add(it.compileDeps)
                    it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps) }
                }
            }
            RootDependencyNodeWithContext(
                // If the incremental cache is on, a separate cache entry is calculated and maintained
                // for every unique combination of parameters:
                //  - module
                //  - leaf-platforms/all-platforms mode
                //  - isTest flag
                rootCacheEntryKey = CacheEntryKey.CompositeCacheEntryKey(listOfNotNull(
                    module.uniqueModuleKey(),
                    "all platforms",
                    isForTests
                )).asRootCacheEntryKey(),
                children = fragmentDependencies,
                templateContext = resolutionSettings.toEmptyContext()
            )
        } else {
            val allPlatformsDependencies = buildList {
                perPlatformsDeps.values.forEach {
                    add(it.compileDeps.toFlatGraph(it.fragment))
                    it.runtimeDeps?.let { runtimeDeps -> add(runtimeDeps.toFlatGraph(it.fragment)) }
                }
            }
            // todo (AB) : [AMPER-4905] It might be useful to decrease the number of changes in logic and make it step-by-step.
            //  At first, we could keep returning plain Graph for fragment dependencies as it is done now in IdeSync.
            //  This way, it might be simpler to adopt changes on Ide plugin side.
            //  As the second step, CLI graph structure might be reused "as is" without flattening.
            ModuleDependencyNodeWithModuleAndContext(
                // ':full' is here to distinguish from CLI resolution,
                // which is leaf-platforms only and should be a separate entry in the cache
                isForTests = isForTests,
                children = allPlatformsDependencies.flatten(),
                module = module,
                templateContext = resolutionSettings.toEmptyContext(),
                topLevel = true,
            )
        }
    }

    private fun ModuleDependencyNodeWithModuleAndContext.toFlatGraph(fragment: Fragment) : List<DirectFragmentDependencyNodeHolderWithContext> {
        val repositories = fragment.module.getValidRepositories().toSet()

        val allDirectDeps = this
            .distinctBfsSequence()
            .filterIsInstance<DirectFragmentDependencyNodeHolderWithContext>()
            .sortedByDescending { it.fragment == this }

        val alreadyAddedCoordinates = mutableSetOf<Pair<MavenCoordinates, Boolean>>()

        val allMavenDeps = buildList {
            allDirectDeps.forEach {
                val coordinatesWithBom = it.dependencyNode.dependency.coordinates to it.dependencyNode.isBom
                if (!it.isTransitive || coordinatesWithBom !in alreadyAddedCoordinates) {
                    // ALL direct fragment deps + other deps from transitive local modules not declared as a direct fragment dependency.
                    val context = adjustContext(fragment, it, repositories)
                    val nodeToAdd = it.toFlattenedFragmentDirectDependencyNode(fragment, context)
                    add(nodeToAdd)
                    alreadyAddedCoordinates.add(coordinatesWithBom)
                }
            }
        }.distinctBy { it.dependencyNode }

        return allMavenDeps
    }

    private fun adjustContext(
        fragment: Fragment,
        directFragmentDependencyNode: DirectFragmentDependencyNodeHolderWithContext,
        repositories: Set<Repository>,
    ): Context =
        if (fragment.module == directFragmentDependencyNode.fragment.module
            || repositories == directFragmentDependencyNode.dependencyNode.context.settings.repositories.toSet()
        ) {
            directFragmentDependencyNode.dependencyNode.context
        } else {
            // Dependency belongs to another module with different list of repositories
            directFragmentDependencyNode.dependencyNode.context.copyWithNewNodeCache(
                parentNodes = emptySet(),
                repositories = repositories.toList()
            )
        }

    private fun DirectFragmentDependencyNodeHolderWithContext.toFlattenedFragmentDirectDependencyNode(
        fragment: Fragment, context: Context
    ): DirectFragmentDependencyNodeHolderWithContext {
        // Todo (AB): Why do we recreate node here? It will be taken from cache anyway.
        val dependencyNode = context.toMavenDependencyNode(notation.toDrMavenCoordinates(), notation is BomDependency)

        val node = DirectFragmentDependencyNodeHolderWithContext(
            dependencyNode,
            fragment = fragment,
            templateContext = context,
            notation = this.notation,
            isTransitive = this.isTransitive
        )

        return node
    }

    companion object {

        private val defaultRepositories = listOf(
            MavenCentralDefaultConfiguration.url,
            "https://maven.google.com/",
        )

        // todo (AB): [AMPER-4905] it's not very good to have long-running statics in IDE plugin
        //  (it might be moved to Context resolution cache instead)
        private val alreadyReportedHttpRepositories = ConcurrentHashMap<String, Boolean>()
        private val alreadyReportedNonHttpsRepositories = ConcurrentHashMap<String, Boolean>()

        /**
         * This method calculates unresolved graphs for every fragment of every module and
         * then performs resolution of dependencies for all project modules.
         *
         * It might be useful to cache the result of the method [Model.moduleDependencies]
         * and call resoltion based on the cached List of [ModuleDependencies] instead of calling this method directly.
         */
        @UsedInIdePlugin
        suspend fun Model.resolveProjectDependencies(
            resolutionSettings: AmperResolutionSettings,
            resolutionRunSettings: ResolutionRunSettings,
            moduleDependencies: List<ModuleDependencies>? = null
        ) = resolveProjectDependencies(
            moduleDependenciesList =
                moduleDependencies
                    ?.checkProjectModules(this@resolveProjectDependencies)
                    ?: moduleDependencies(resolutionSettings.copy(includeNonExportedNative = false)),
            resolutionRunSettings = resolutionRunSettings,
            projectRoot = projectRoot.root,
        )

        @UsedInIdePlugin
        fun buildProjectDependenciesGraph(
            model: Model,
            moduleDependenciesList: List<ModuleDependencies>
        ): List<DependencyNodeHolderWithContext> {
            moduleDependenciesList.checkProjectModules(model)
            return buildProjectDependenciesGraph(moduleDependenciesList)
        }

        @UsedInIdePlugin
        fun buildProjectDependenciesGraph(
            model: Model,
            moduleDependencies: ModuleDependencies
        ): List<DependencyNodeHolderWithContext> {
            moduleDependencies.checkProjectModule(model)
            return buildProjectDependenciesGraph(listOf(moduleDependencies))
        }

        private fun buildProjectDependenciesGraph(
            moduleDependenciesList: List<ModuleDependencies>
        ): List<DependencyNodeHolderWithContext> {
            return buildList {
                moduleDependenciesList.forEach {
                    // test and main fragments are resolved separately
                    add(it.allFragmentsGraph(isForTests = false, flattenGraph = true))
                    add(it.allFragmentsGraph(isForTests = true, flattenGraph = true))
                }
            }
        }

        /**
         * This is an entry point into the module-wide resolution of the project.
         * It resolves dependencies of all modules independently.
         *
         * Versions of dependency are aligned across all module main fragments and across all module test fragments.
         * I.e., different main fragments of the module can't have different versions of the same library
         * in their resolved graphs (including transitive).
         * And the same is true for test fragments.
         * At the same time, main and test fragments of the module could have different versions of the same library
         * in their resolved graphs.
         * Note: The latter is subject to change (ideally, it should be enforced that test fragments have dependencies of the same
         * versions as main fragments, but could not cause overriding of the versions of main fragments dependencies)
         *
         * Two different modules could have different versions of the same library in their resolved graphs (including transitive).
         */
        private suspend fun resolveProjectDependencies(
            moduleDependenciesList: List<ModuleDependencies>,
            resolutionRunSettings: ResolutionRunSettings,
            projectRoot: Path,
        ): ResolvedGraph {
            val [openTelemetry, incrementalCache, fileCacheBuilder] = moduleDependenciesList.firstOrNull()
                ?.let {
                    Triple(it.resolutionSettings.openTelemetry,
                        it.resolutionSettings.incrementalCache,
                        it.resolutionSettings.fileCacheBuilder)
                }
                ?: return ResolvedGraph(RootDependencyNodeStub(), null)

            val filter = ModuleResolutionFilter(resolutionType = ResolutionType.ALL)
            // Wrapping into per-project cache entry
            // Goal: if nothing has changed, check inputs once, instead of checking inputs for every module where
            //       one library from shared module is checked as an input as many times as many modules depend on it transitively
            return with(resolutionRunSettings) {
                openTelemetry.spanBuilder("DR: Resolving project dependencies").use {
                    val moduleGraphs = buildProjectDependenciesGraph(moduleDependenciesList)

                    val resolutionId = CacheEntryKey.CompositeCacheEntryKey(
                        listOf(
                            "Project dependencies",
                            resolutionRunSettings.resolutionDepth,
                            resolutionRunSettings.resolutionLevel,
                            resolutionRunSettings.downloadSources,
                            projectRoot.absolutePathString()
                        )
                    ).computeKey()

                    if (incrementalCacheUsage == IncrementalCacheUsage.SKIP
                        || incrementalCache == null
                    ) {
                        resolveDependenciesBatch(moduleGraphs, filter)
                    } else {
                        val graphEntryKeys = moduleGraphs.flatMap { it.getDependenciesGraphInput() }
                        if (graphEntryKeys.contains(CacheEntryKey.NotCached)) {
                            resolveDependenciesBatch(moduleGraphs, filter)
                        } else {
                            val cacheInputValues = mapOf(
                                "userCacheRoot" to moduleGraphs.first().context.settings.fileCache.amperCache.pathString,
                                @Suppress("REDUNDANT_SINGLE_EXPRESSION_STRING_TEMPLATE") // KT-86705
                                "dependencies" to graphEntryKeys.joinToString("|") { "${it.computeKey()}" },
                            )

                            incrementalCache.execute(
                                key = resolutionId,
                                inputValues = cacheInputValues,
                                inputFiles = emptyList(),
                                serializer = DependencyGraph.serializer(),
                                json = GraphJson.json,
                                forceRecalculation = (incrementalCacheUsage == IncrementalCacheUsage.REFRESH_AND_USE),
                            ) {
                                openTelemetry.spanBuilder("DR.graph:resolution multi-module")
                                    .use {
                                        val compositeGraph = resolveDependenciesBatch(moduleGraphs, filter)
                                        val serializableGraph = compositeGraph.root.toGraph()

                                        ResultWithSerializable(
                                            outputValue = serializableGraph,
                                            outputFiles = compositeGraph.root.children.flatMap { it.dependencyPaths() },
                                            expirationTime = compositeGraph.expirationTime,
                                        )
                                    }
                            }.let { result ->
                                try {
                                    result.outputValue.root.fillNotation(
                                        RootDependencyNodeWithContext(
                                            children = moduleGraphs,
                                            templateContext = emptyContext(fileCacheBuilder, openTelemetry, incrementalCache),
                                        )
                                    )
                                    ResolvedGraph(result.outputValue.root, result.expirationTime)
                                } catch (e: Exception) {
                                    logger.error("Unable to post-process the serializable dependency graph. " +
                                            "Falling back to uncached resolution", e)
                                    resolveDependenciesBatch(moduleGraphs, filter)
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * This is an entry point into the module-wide resolution.
         * It resolves dependencies of given modules independently.
         *
         * Versions of dependency are aligned across all module main fragments and across all module test fragments.
         * I.e., different main fragments of the module can't have different versions of the same library
         * in their resolved graphs (including transitive).
         * And the same is true for test fragments.
         * At the same time, main and test fragments of the module could have different versions of the same library
         * in their resolved graphs.
         * Note: The latter is subject to change (ideally, it should be enforced that test fragments have dependencies of the same
         * versions as main fragments, but could not cause overriding of the versions of main fragments dependencies)
         *
         * Two different modules could have different versions of the same library in their resolved graphs (including transitive).
         */
        suspend fun resolveModuleDependencies(
            modules: List<AmperModule>,
            resolutionSettings: AmperResolutionSettings,
            resolutionRunSettings: ResolutionRunSettings = defaultResolutionRunSettings,
            leafPlatformsOnly: Boolean = false,
            filter: ModuleResolutionFilter = defaultModuleResolutionFilter,
        ): ResolvedGraph {
            val sharedResolutionCache = Cache()
            val moduleDependenciesList = modules.map { ModuleDependencies(it, resolutionSettings, sharedResolutionCache) }

            return resolveModuleDependencies(
                moduleDependenciesList = moduleDependenciesList,
                resolutionRunSettings,
                leafPlatformsOnly,
                filter,
            )
        }

        suspend fun resolveModuleDependencies(
            moduleDependenciesList: List<ModuleDependencies>,
            resolutionRunSettings: ResolutionRunSettings = defaultResolutionRunSettings,
            leafPlatformsOnly: Boolean,
            filter: ModuleResolutionFilter,
        ): ResolvedGraph {
            val openTelemetry = moduleDependenciesList.first().resolutionSettings.openTelemetry
            return openTelemetry.spanBuilder("DR: Resolving dependencies for the list of modules").use {
                val moduleGraphs = buildList {
                    moduleDependenciesList.forEach {
                        if (leafPlatformsOnly) {
                            //  1. Tests and main should be resolved separately in IdeSync-mode
                            if (filter.resolutionType.includeMain) {
                                add(it.allLeafPlatformsGraph(isForTests = false))
                            }
                            if (filter.resolutionType.includeTest) {
                                add(it.allLeafPlatformsGraph(isForTests = true))
                            }
                        } else {
                            //  1. Tests and main should be resolved separately in IdeSync-mode
                            if (filter.resolutionType.includeMain) {
                                add(it.allFragmentsGraph(isForTests = false, flattenGraph = false))
                            }
                            if (filter.resolutionType.includeTest) {
                                add(it.allFragmentsGraph(isForTests = true, flattenGraph = false))
                            }
                        }
                    }
                }

                resolutionRunSettings.resolveDependenciesBatch(moduleGraphs, filter)
            }
        }

        private suspend fun ResolutionRunSettings.resolveDependenciesBatch(
            moduleGraphs: List<DependencyNodeHolderWithContext>,
            filter: ModuleResolutionFilter,
        ): ResolvedGraph {
            val resolvedGraphs = coroutineScope {
                moduleGraphs.map {
                    async {
                        it.resolveDependencies(this@resolveDependenciesBatch)
                    }
                }
            }.awaitAll()

            val resolvedGraphsUnwrapped =
                resolvedGraphs
                    .flatMap { if (it.root is RootDependencyNode) it.root.children else listOf(it.root) }
                    .filter {
                        with(filter) {
                            // todo (AB) : [AMPER-4905] This filtering works for non-flattened list only.
                            it is ModuleDependencyNode
                                    && (platforms == null || platforms == it.resolutionConfig.platforms)
                                    && (scope == null || scope == it.resolutionConfig.scope)
                        }
                    }

            // todo (AB) : [AMPER-4905] Introduce context-unaware ModuleDependencyNode that aggregates context-specific
            //  resolution results. Publicly visible children should be filtered according to given filter,
            //  but all children should be internally available as well for calculating overriddenBy insights.
            val compositeGraph = ResolvedGraph(
                RootDependencyNodeStub(children = resolvedGraphsUnwrapped).also { root ->
                    root.children.filterIsInstance<ModuleDependencyNode>().forEach {
                        it.attachToNewRoot(root)
                    }
                },
                resolvedGraphs.mapNotNull { it.expirationTime }.minByOrNull { it },
            )
            return compositeGraph
        }

        /**
         * Resolve dependencies of the given [DependencyNodeHolderWithContext]
         *
         * This function should be kept private as it exposes low-level resolution API.
         */
        private suspend fun DependencyNodeHolderWithContext.resolveDependencies(
            resolutionRunSettings: ResolutionRunSettings,
        ): ResolvedGraph {
            val root = this@resolveDependencies
            return with(resolutionRunSettings) {
                context.infoSpanBuilder("DR.graph:resolveDependencies").use {
                    when (resolutionDepth) {
                        ResolutionDepth.GRAPH_ONLY -> {
                            /* Do nothing, graph is already given */
                            ResolvedGraph(
                                root,
                                null
                            )
                        }

                        ResolutionDepth.GRAPH_WITH_DIRECT_DEPENDENCIES,
                        ResolutionDepth.GRAPH_FULL,
                            -> {
                            val resolvedGraph = Resolver().resolveDependencies(
                                root = root,
                                resolutionLevel,
                                downloadSources,
                                resolutionDepth != ResolutionDepth.GRAPH_WITH_DIRECT_DEPENDENCIES,
                                incrementalCacheUsage = incrementalCacheUsage,
                                DirectMavenDependencyUnspecifiedVersionResolver(),
                                postProcessGraph = {
                                    // Merge the input graph (that has PSI references) with the deserialized one
                                    it.fillNotation(root)
                                }
                            )
                            resolvedGraph
                        }
                    }
                }
            }
        }

        private fun SerializableDependencyNode.fillNotation(sourceNode: DependencyNodeHolderWithContext) {
            val sourceDirectDeps = sourceNode.children.groupBy { it.key }
            this.children.forEach { serializableNode ->
                when (serializableNode) {
                    is SerializableDirectFragmentDependencyNodeHolder -> {
                        val originalMavenCoordinates = serializableNode.dependencyNode.getOriginalMavenCoordinates()
                        val sourceNode = sourceDirectDeps[serializableNode.key].resolveCorrespondingSourceNode<DirectFragmentDependencyNodeHolderWithContext>(serializableNode) {
                            originalMavenCoordinates == notation.coordinates.toDrMavenCoordinates()
                        }
                        serializableNode.notation = sourceNode.notation
                    }
                    is SerializableModuleDependencyNodeWithModule -> {
                        val sourceNode = sourceDirectDeps[serializableNode.key].resolveCorrespondingSourceNode<ModuleDependencyNodeWithModuleAndContext>(serializableNode)
                        serializableNode.notation = sourceNode.notation
                        serializableNode.fillNotation(sourceNode)
                    }
                    is SerializableRootDependencyNode -> {
                        val sourceNode = sourceDirectDeps[serializableNode.key].resolveCorrespondingSourceNode<RootDependencyNodeWithContext>(serializableNode)
                        serializableNode.fillNotation(sourceNode)
                    }
                }
            }
        }

        private inline fun <reified T : DependencyNode> List<DependencyNode>?.resolveCorrespondingSourceNode(
            node: SerializableDependencyNode,
            additionalMatch: T.() -> Boolean = { true }
        ): T {
            if (this.isNullOrEmpty())
                error("Deserialized node with key ${node.key} has no corresponding input node")

            val matchedNodes = this.filter {
                (it as? T) ?: error(
                    "Deserialized node corresponds to unexpected input node of type " +
                            "${this::class.simpleName} while ${node::class.simpleName} is expected"
                )
                it.additionalMatch()
            }.takeIf { it.isNotEmpty() } ?: this

            if (matchedNodes.size > 1)
                logger.warn("Found ${matchedNodes.size} matching nodes for ${node.key} while a single node is expected")
            return matchedNodes.first() as T
        }

        /**
         * Provide unresolved [ModuleDependencies] for all modules of the given AOM [Model].
         *
         * This method could be used for caching [ModuleDependencies] per AOM instance to avoid recalculation of
         * direct graph for the same model.
         *
         * The resulting list could be used as an entry point into module-wide dependency resoluion
         * for the entire project (represented by the given [Model]) which is performed by [resolveProjectDependencies]
         */
        fun Model.moduleDependencies(resolutionSettings: AmperResolutionSettings): List<ModuleDependencies> {
            val sharedResolutionCache = Cache()
            return modules.map {
                ModuleDependencies(it, resolutionSettings, sharedResolutionCache)
            }
        }

        /**
         * Provide unresolved [ModuleDependencies] for the given module [AmperModule]
         *
         * This method could be used for caching [ModuleDependencies] per module instance to avoid recalculation of
         * direct graph for the same module.
         * Also, it is useful in case an upstream cache based on a module dependency graph is built.
         * Returned [ModuleDependencies] provides inputs that can be used for upstream cache entry without a need to restore the graph itself
         * (restoring the upstream cache entry only).
         *
         * The resulting list could be used as an entry point into module-wide dependency resolution for the module
         */
        fun AmperModule.moduleDependencies(resolutionSettings: AmperResolutionSettings): ModuleDependencies {
            val sharedResolutionCache = Cache()
            return ModuleDependencies(this, resolutionSettings, sharedResolutionCache)
        }

        /**
         * Returns a dependencies sequence of the given module in the resolution scope
         * of the given [platform], [isTest] and [dependencyReason].
         */
        fun AmperModule.getDependentAmperModules(
            isTest: Boolean,
            platform: Platform,
            dependencyReason: ResolutionScope,
            userCacheRoot: AmperUserCacheRoot,
            incrementalCache: IncrementalCache,
            openTelemetry: OpenTelemetry?,
        ): Sequence<AmperModule> {
            val resolutionSettings = AmperResolutionSettings(userCacheRoot, incrementalCache, openTelemetry)
            val moduleDependencies = moduleDependencies(resolutionSettings)
            return moduleDependencies
                .forPlatform(platform = platform, isTest = isTest)
                .forScope(scope = dependencyReason)
                .getModuleDependencies()
        }

        /**
         * Returns all fragments the given one depends on (including [Fragment] dependencies from other modules).
         */
        fun Fragment.getSymbolsVisibilityFragmentsDependencies(
            moduleDependencies: ModuleDependencies,
        ): Sequence<Fragment> {
            if (module != moduleDependencies.module)
                error("Given fragment $name belongs to module ${module.userReadableName}, " +
                        "but moduleDependencies for the module ${moduleDependencies.module.userReadableName} were passed")

            val otherModules = moduleDependencies.getSymbolsVisibilityDependentAmperModules(isTest, platforms).toSet()
            return allFragmentDependencies() +
                    otherModules.flatMap { module -> module.fragmentsToDependOnFromOtherModuleFragmentWith(platforms) }
        }

        /**
         * Returns a dependencies sequence of the given module in the resolution scope
         * of the given [platforms], [isTest] and [dependencyReason].
         */
        private fun ModuleDependencies.getSymbolsVisibilityDependentAmperModules(
            isTest: Boolean,
            platforms: Set<Platform>
        ): Sequence<AmperModule> {
            return forPlatforms(platforms = platforms, isTest = isTest)
                .compileSymbolsVisibilityDeps
                .getModuleDependencies()
        }

        private fun ModuleDependencyNodeWithModuleAndContext.getModuleDependencies(): Sequence<AmperModule> {
            return distinctBfsSequence { child, _ ->  child is ModuleDependencyNodeWithModuleAndContext }
                .drop(1)
                .filterIsInstance<ModuleDependencyNodeWithModuleAndContext>()
                .map { it.module }
        }

        /**
         * Provide unresolved [ModuleDependencies] for all modules of the given AOM [Model].
         *
         * This method could be used for caching [ModuleDependencies] per AOM instance to avoid recalculation of
         * direct graph for the same model.
         *
         * The resulting list could be used as an entry point into module-wide dependency resoluion for the entire project
         * (represented by the given [Model])
         */
        private fun List<ModuleDependencies>.checkProjectModules(aom: Model): List<ModuleDependencies> = also {
            if (aom.modules.toSet() != this.map { it.module }.toSet())
                error("Modules from the given list do not match modules from the Project Model")
            forEach { it.checkProjectModule(aom) }
            return this
        }

        private fun ModuleDependencies.checkProjectModule(aom: Model): ModuleDependencies = also {
            if (!aom.modules.contains(this.module))
                error("The given module ${this.module.userReadableName} does not belong to the given Project Model")
            if (it.resolutionSettings.includeNonExportedNative)
                error("Modules from the given list should be configured NOT to include non-exported native dependencies in COMPILE classpath")
        }

        internal fun AmperModule.getValidRepositories(): List<Repository> {
            val acceptedRepositories = mutableListOf<Repository>()
            for (repository in resolvableRepositories()) {
                if (repository is MavenRepository) {
                    @Suppress("HttpUrlsUsage")
                    if (repository.url.startsWith("http://")) {
                        // TODO: Special --insecure-http-repositories option or some flag in project.yaml
                        // to acknowledge http:// usage

                        // report only once per `url`
                        if (alreadyReportedHttpRepositories.put(repository.url, true) == null) {
                            logger.warn("http:// repositories are not secure and should not be used: ${repository.url}")
                        }

                        continue
                    }

                    if (!repository.url.startsWith("https://") && repository != MavenLocal) {

                        // report only once per `url`
                        if (alreadyReportedNonHttpsRepositories.put(repository.url, true) == null) {
                            logger.warn("Non-https repositories are not supported, skipping url: ${repository.url}")
                        }

                        continue
                    }
                }

                acceptedRepositories.add(repository)
            }

            return acceptedRepositories
        }

        private fun AmperModule.resolvableRepositories(): List<Repository> =
            parts
                .filterIsInstance<RepositoriesModulePart>()
                .firstOrNull()
                ?.mavenRepositories
                ?.filter { it.resolve }
                ?.map { it.toRepository() }
                ?: defaultRepositories.map { it.toRepository() }

        fun RepositoriesModulePart.Repository.toRepository() = when {
            this.url == SpecialMavenLocalUrl -> MavenLocal
            else -> MavenRepository(url, userName, password)
        }

        internal fun String.toRepository() = RepositoriesModulePart.Repository(this, this).toRepository()
    }
}

fun List<ModuleDependencyNode>.fragmentDependencies(module: String, fragment: String, aom: Model) : List<ModuleDependencyNode> {
    val amperModule = aom.modules.singleOrNull { it.userReadableName == module } ?: error("Module $module is not found")
    val fragment = amperModule.fragments.singleOrNull { it.name == fragment } ?: error("Fragment $fragment does not exist in module $module")

    val platforms = fragment.platforms.map { it.toResolutionPlatform()!! }.toSet()
    val isTest = fragment.isTest

    return filter {
        it.moduleName == module
                && it.resolutionConfig.platforms == platforms
                && it.isForTests == isTest
    }
}

/**
 * This class provides unresolved fragment RUNTIME and COMPILE dependencies.
 */
class PerFragmentDependencies(
    val fragment: Fragment,
    resolutionSettings: AmperResolutionSettings,
    sharedResolutionCache: Cache,
) {
    /**
     * This node represents a graph that contains external COMPILE dependencies of the module for the particular platform.
     * It contains direct external dependencies of this module as well as exported dependencies of dependent modules
     * accessible from this module.
     * It doesn't contain transitive external dependencies (no resolution happened actually).
     * This graph is a part of the input for dependency resolution of the module.
     * See [ModuleDependencies.allLeafPlatformsGraph] for further details.
     */
    internal val compileDeps: ModuleDependencyNodeWithModuleAndContext by lazy {
        fragment.module.buildDependenciesGraph(
            isTest = fragment.isTest,
            platforms = fragment.platforms,
            dependencyReason = ResolutionScope.COMPILE,
            resolutionSettings = resolutionSettings,
            sharedResolutionCache = sharedResolutionCache,
        )
    }

    /**
     * This node represents a graph that contains external RUNTIME dependencies of the module for the particular platform.
     * It contains direct external dependencies of this module as well as direct external dependencies of all modules
     * this one depends on transitively.
     * It doesn't contain transitive external dependencies although (no resolution happened actually).
     * This graph is a part of the input for dependency resolution of the module.
     * See [ModuleDependencies.allLeafPlatformsGraph] for further details.
     */
    internal val runtimeDeps: ModuleDependencyNodeWithModuleAndContext? by lazy {
        when {
            fragment.platforms.singleOrNull()?.isDescendantOf(Platform.NATIVE) == true
                    && resolutionSettings.includeNonExportedNative -> null  // The native world doesn't distinguish compile/runtime classpath
            else -> fragment.module.buildDependenciesGraph(
                isTest = fragment.isTest,
                platforms = fragment.platforms,
                dependencyReason = ResolutionScope.RUNTIME,
                resolutionSettings = resolutionSettings,
                sharedResolutionCache = sharedResolutionCache,
            )
        }
    }

    /**
     * This node represents a graph that contains external COMPILE dependencies of the module for the particular platform.
     * It contains direct external dependencies of this module as well as exported dependencies of dependent modules
     * accessible from this module.
     * It doesn't contain transitive external dependencies (no resolution happened actually).
     *
     * The graph could be used for getting compile dependencies which declaration are visible in this fragment.
     * (non-exported transitive dependencies for native fragments are not included).
     * It is used for metadata compilation.
     */
    internal val compileSymbolsVisibilityDeps: ModuleDependencyNodeWithModuleAndContext by lazy {
        when {
            fragment.platforms.singleOrNull()?.isDescendantOf(Platform.NATIVE) == true
                    && resolutionSettings.includeNonExportedNative ->
            // The native world doesn't distinguish compile/runtime classpath,
            // but IDE and metadata-compilation operates with java-like compile classpath to restrict symbols visibility
            fragment.module.buildDependenciesGraph(
                isTest = fragment.isTest,
                platforms = fragment.platforms,
                dependencyReason = ResolutionScope.COMPILE,
                resolutionSettings = resolutionSettings.copy(includeNonExportedNative = false),
                sharedResolutionCache = sharedResolutionCache,
            )
            else -> compileDeps
        }
    }

    internal fun forScope(scope: ResolutionScope) = when (scope) {
        ResolutionScope.COMPILE -> compileDeps
        ResolutionScope.RUNTIME -> runtimeDeps ?: compileDeps
    }

    val compileDepsCoordinates: List<MavenCoordinates> by lazy { compileDeps.getExternalDependencies() }
    val runtimeDepsCoordinates: List<MavenCoordinates> by lazy { runtimeDeps?.getExternalDependencies() ?: emptyList() }

    val compileDirectDepsCoordinates: List<MavenCoordinates> by lazy { compileDeps.getExternalDependencies(true) }

    private fun AmperModule.buildDependenciesGraph(
        isTest: Boolean,
        platforms: Set<Platform>,
        dependencyReason: ResolutionScope,
        resolutionSettings: AmperResolutionSettings,
        sharedResolutionCache: Cache,
    ): ModuleDependencyNodeWithModuleAndContext {
        val resolutionPlatform = platforms.map { it.toResolutionPlatform()
            ?: throw IllegalArgumentException("Dependency resolution is not supported for the platform $it") }.toSet()

        val classpathResolutionFlow = Classpath(DependenciesFlowType.ClassPathType(
            dependencyReason, resolutionPlatform, isTest, resolutionSettings.includeNonExportedNative
        ))

        return classpathResolutionFlow.directDependenciesGraph(
            this@buildDependenciesGraph, resolutionSettings, sharedResolutionCache
        )
    }
}

enum class ResolutionType(
    val includeMain: Boolean,
    val includeTest: Boolean,
) {
    TEST(false, true),
    MAIN(true, false),
    ALL(true, true);
}

/**
 * Defines resolution settings that define the behavior of the particular resolution run.
 */
data class ResolutionRunSettings(
    val resolutionDepth: ResolutionDepth = ResolutionDepth.GRAPH_FULL,
    val resolutionLevel: ResolutionLevel = ResolutionLevel.NETWORK,
    val downloadSources: Boolean = false,
    val incrementalCacheUsage: IncrementalCacheUsage = IncrementalCacheUsage.USE,
)

val defaultResolutionRunSettings = ResolutionRunSettings()

/**
 * Defines project-wide resolution settings that don't vary from one resolution run to another.
 * It doesn't contain context-specific settings like scope and platform.
 */
data class AmperResolutionSettings(
    val userCacheRoot: AmperUserCacheRoot,
    val incrementalCache: IncrementalCache? = null,
    val openTelemetry: OpenTelemetry? = null,

    /**
     * Compilation of a module for a native platform requires all transitive dependencies (for linkage phase),
     * even those dependencies that contain symbols not visible in the module,
     * i.e., non-exported dependencies of dependent modules.
     * This way the world of native doesn't distinguish between COMPILE/RUNTIME classpath as Java world does.
     *
     * But resolving dependencies for the native platform for IDE
     * requires separating dependencies with symbols that might be used in
     * module source code from those that could not.
     * For that case, this flag should be set to <code>true</code>.
     *
     * This will cause Java-like resolution of native dependencies
     * where resulting dependencies for COMPILE scope will contain dependencies "visible" from the module only,
     * and the RUNTIME graph will contain all dependencies.
     */
    val includeNonExportedNative: Boolean = true,
) {
    val fileCacheBuilder: FileCacheBuilder.() -> Unit = getAmperFileCacheBuilder(userCacheRoot)
}

private fun AmperResolutionSettings.toEmptyContext(scope: ResolutionScope? = null) = emptyContext(
    userCacheRoot = userCacheRoot,
    openTelemetry = openTelemetry,
    incrementalCache = incrementalCache,
)