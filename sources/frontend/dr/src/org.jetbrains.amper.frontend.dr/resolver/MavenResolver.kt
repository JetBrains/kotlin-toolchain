/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.JavaVersion
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.Repository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.ResolvedGraph
import org.jetbrains.amper.dependency.resolution.Resolver
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeWithContext
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.collectBuildProblems
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.plus
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import kotlin.io.path.pathString

/**
 * An adapter around [Resolver] that creates root node,
 * configures its context and span properly, before performing actual resolve.
 */
open class MavenResolver(
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
) {
    /**
     * Perform resolution over a set of maven coordinates.
     */
    context(_: ProblemReporter)
    suspend fun resolve(
        coordinates: List<MavenCoordinates>,
        repositories: List<Repository>,
        scope: ResolutionScope,
        platform: ResolutionPlatform,
        resolveSourceMoniker: String,
        transitive: Boolean = true,
    ): ResolvedGraph = resolveBomAware(repositories, scope, platform, resolveSourceMoniker,
        mavenCoordinates = coordinates.map { MavenCoordinatesExt(it) },
        transitive = transitive,
    )

    /**
     * Create a [Context] and resolve dependencies of the passed [mavenCoordinates].
     * Also, create respective span.
     */
    context(_: ProblemReporter)
    suspend fun resolveBomAware(
        repositories: List<Repository>,
        scope: ResolutionScope,
        platform: ResolutionPlatform,
        resolveSourceMoniker: String,
        resolutionDepth: ResolutionDepth = ResolutionDepth.GRAPH_FULL,
        jvmRelease: JavaVersion? = null,
        mavenCoordinates: List<MavenCoordinatesExt>,
        transitive: Boolean = true,
    ): ResolvedGraph = spanBuilder("mavenResolve")
        .setAttribute("repositories", repositories.joinToString(" "))
        .setAttribute("user-cache-root", userCacheRoot.path.pathString)
        .setAttribute("scope", scope.name)
        .setAttribute("platform", platform.name)
        .setAttribute("resolutionDepth", resolutionDepth.name)
        .setAttribute("transitive", transitive)
        .apply { setAttribute("nativeTarget", platform.nativeTarget ?: return@apply) }
        .apply { setAttribute("wasmTarget", platform.wasmTarget ?: return@apply) }
        .use {
            val context = Context {
                this.cache = getAmperFileCacheBuilder(userCacheRoot)
                this.repositories = repositories
                this.scope = scope
                this.platforms = setOf(platform)
                this.openTelemetry = GlobalOpenTelemetry.get()
                this.incrementalCache = this@MavenResolver.incrementalCache
                this.jdkVersion = jvmRelease
            }
            val root = RootDependencyNodeWithContext(
                templateContext = context,
                children = mavenCoordinates.map { context.toMavenDependencyNode(it.coordinates, it.isBom) }
            )

            resolveAndReport(resolveSourceMoniker) {
               Resolver().resolveDependencies(root, transitive = transitive)
            }
        }

    /**
     * Perform a resolution over a passed [root].
     */
    context(problemReporter: ProblemReporter)
    private suspend fun resolveAndReport(
        resolveSourceMoniker: String,
        resolve: suspend () -> ResolvedGraph
    ): ResolvedGraph =
        resolve()
            .also { resolvedGraph ->
                // Collecting diagnostics from the resolved graph
                val collecting = CollectingProblemReporter()
                collectBuildProblems(graph = resolvedGraph.root, collecting + problemReporter, Level.Warning)
                handleProblems(collecting.problems, resolveSourceMoniker)
            }

    /**
     * Perform a resolution of module dependencies [moduleDependencies].
     */
    context(_: ProblemReporter)
    suspend fun resolve(
        moduleDependencies: ModuleDependencies,
        isTest: Boolean,
        resolveSourceMoniker: String = "module ${moduleDependencies.module.userReadableName}",
        leafPlatformsOnly: Boolean = true,
        resolutionDepth: ResolutionDepth = ResolutionDepth.GRAPH_FULL,
    ): ResolvedGraph = resolveAndReport(resolveSourceMoniker) {
        ModuleDependencies.resolveModuleDependencies(
            moduleDependenciesList = listOf(moduleDependencies),
            resolutionRunSettings = ResolutionRunSettings(resolutionDepth = resolutionDepth),
            leafPlatformsOnly = leafPlatformsOnly,
            filter = ModuleResolutionFilter(resolutionType = if(isTest) ResolutionType.TEST else ResolutionType.MAIN),
        )
    }

    // todo (AB): [KTC-5270]
    //  Check all usages of this non-cli [MavenResolver] and figure out who and how handles the errors?
    //  make sure ERROR diagnostics are not left unattended
    protected open fun handleProblems(buildProblems: List<BuildProblem>, resolveSourceMoniker: String) =
        Unit
}

fun ResolvedGraph.toIncrementalCacheResult() =
    IncrementalCache.ExecutionResult(outputFiles = root.dependencyPaths(), expirationTime = expirationTime)

fun DependencyNode.getExternalDependencies(directOnly: Boolean = false): List<MavenCoordinates> {
    val uniqueDependencies = buildSet { fillExternalDependencies(this, directOnly) }
    return uniqueDependencies.sortedBy { it.toString() }
}

private fun DependencyNode.fillExternalDependencies(
    dependencies: MutableSet<MavenCoordinates>,
    directOnly: Boolean = false,
) {
    children.forEach {
        // There can be all sorts of wrapper types here, which are somewhat internal to the dependency resolution module.
        // We only want to add external maven dependencies here anyway, or recurse, so let's not enumerate.
        if (it is MavenDependencyNode) {
            dependencies.add(it.mavenCoordinates())
        } else if (!directOnly) {
            it.fillExternalDependencies(dependencies, directOnly = false)
        }
    }
}

data class MavenCoordinatesExt(
    val coordinates: MavenCoordinates,
    val isBom: Boolean = false,
)