/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.flow

import org.jetbrains.amper.dependency.resolution.Cache
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.DefaultScopedNotation
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.MavenDependency
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.SwiftPMDependencyNotation
import org.jetbrains.amper.frontend.allFragmentDependencies
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.DependenciesFlowType
import org.jetbrains.amper.frontend.dr.resolver.DependencyNodeHolderWithNotationAndContext
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNodeWithModuleAndContext
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.SwiftPMDependencyNodeFromAmperModuleImpl
import org.jetbrains.amper.frontend.fragmentsTargeting
import org.jetbrains.amper.frontend.fragmentsToDependOnFromOtherModuleFragmentWith
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.swiftpm.SwiftPMDependency

/**
 * Performs the initial resolution of module classpath dependencies.
 * The resulting graph contains a node for every module dependency matching the resolution scope
 * as well as direct maven dependencies of that module.
 *
 * It doesn't download anything.
 *
 * Graph:
 * ```
 * ┌────────────┐
 * │amper-module├────────┐───────────────────┐───────────────────────┐
 * └──┬─────────┘        │                   │                       │
 *    │                  │                   │                       │
 *    │                  │                   │                       │
 * ┌──▼──────────┐     ┌─▼───────────┐     ┌─▼───────────────┐     ┌─▼───────────────┐
 * │amper-module1│...  │amper-moduleN│     │maven dependency1│...  │maven dependencyM│
 * └──┬──────────┘     └─┬───────────┘     └─────────────────┘     └─────────────────┘
 *
 * ```
 *
 * Resolution of the classpath dependencies graph takes the following steps:
 *
 * 1. Resolve complete fragment dependencies graph containing dependency on all other fragments transitively:
 * - adding all fragment dependencies from the same module
 *   (let's call the resulted set as ModuleFragmentDependencies)
 * - adding all direct dependencies on the other modules fragments (for every fragment from ModuleFragmentDependencies)
 * - adding all direct dependencies on the other modules fragments either marked with the flag 'exported'
 *   or unconditionally for native modules (since the native module compilation classpath includes all transitive dependencies)
 *                  and for the runtime resolution scope
 *   (for every fragment from the previous step)
 * - repeating the last step until newly added fragments have exported dependencies
 *   => resulting set is a complete list of transitive fragment dependencies.
 *
 * 2. Now, walk through the resulting fragment dependencies graph and resolve actual maven dependencies
 * - adding maven dependencies of all fragments from ModuleFragmentDependencies unconditionally
 * - adding maven dependencies marked with the flag 'exported' for all the rest fragments from the graph
 *   or unconditionally for native modules (since the native module compilation classpath includes all transitive dependencies)
 *                  and for the runtime resolution scope
 */
// todo (AB) : [AMPER-4905] Get rid of inheritance and move closer to the ModuleDependencies location.
internal class Classpath(
    dependenciesFlowType: DependenciesFlowType.ClassPathType
): AbstractDependenciesFlow<DependenciesFlowType.ClassPathType>(dependenciesFlowType) {

    override fun directDependenciesGraph(
        module: AmperModule,
        resolutionSettings: AmperResolutionSettings,
        sharedResolutionCache: Cache,
    ): ModuleDependencyNodeWithModuleAndContext {
        return module.fragmentsModuleDependencies(resolutionSettings = resolutionSettings, sharedResolutionCache = sharedResolutionCache)
    }

    private fun Platform.toSwiftPMPlatform(): SwiftPMDependency.Platform {
        return when {
            isDescendantOf(Platform.IOS) -> SwiftPMDependency.Platform.iOS
            isDescendantOf(Platform.MACOS) -> SwiftPMDependency.Platform.macOS
            isDescendantOf(Platform.TVOS) -> SwiftPMDependency.Platform.tvOS
            isDescendantOf(Platform.WATCHOS) -> SwiftPMDependency.Platform.watchOS
            else -> error("Non Apple platform $this")
        }
    }

    private fun AmperModule.fragmentsModuleDependencies(
        directDependencies: Boolean = true,
        notation: LocalModuleDependency? = null,
        visitedModules: MutableSet<AmperModule> = mutableSetOf(),
        initialFragment: Fragment? = null,
        resolutionSettings: AmperResolutionSettings,
        sharedResolutionCache: Cache,
    ): ModuleDependencyNodeWithModuleAndContext {

        visitedModules.add(this)

        val moduleContext = resolveModuleContext(flowType.platforms, flowType.scope, flowType.isTest, resolutionSettings, sharedResolutionCache)

        val resolutionPlatforms = moduleContext.settings.platforms

        val platforms = resolutionPlatforms.map { it.toPlatform() }.toSet()
        val allMatchingFragments = if (directDependencies) {
            // If we are collecting direct dependencies, then we collect fragments targeting the resolution
            // parameters.
            this.fragmentsTargeting(platforms, isTest = flowType.isTest)
        } else {
            // If we are collecting dependencies transitively, we have different contract on how those are picked
            // which matches the external Maven dependency resolution rules.
            this.fragmentsToDependOnFromOtherModuleFragmentWith(platforms)
        }

        if (initialFragment != null && initialFragment.module.userReadableName != this.userReadableName)
            error ("Given initialFragment doesn't belong to given module")

        val fragments = initialFragment
            ?.allFragmentDependencies(true)
            // it would be better to use simple intersect with allMatchingFragments here, but Fragment.equals is not correctly defined yet
            ?.filter { it.name in allMatchingFragments.map { it.name } }
            ?.toList()
            ?: allMatchingFragments

        val dependencies = fragments
            .sortedForClasspath(platforms)
            .flatMap { it.toDependencyNode(resolutionPlatforms, directDependencies, moduleContext, visitedModules, resolutionSettings, sharedResolutionCache) }
            .sortedByDescending { (it.notation as? DefaultScopedNotation)?.exported == true }

        val firstAppleFragment = this.fragments.filterIsInstance<LeafFragment>().sortedBy { it.name }.firstOrNull {
            it.platform.isDescendantOf(Platform.APPLE) == true
        }
        val swiftPMDependencies = if (
            resolutionSettings.includeSwiftPMDependencies
            && moduleContext.settings.scope == ResolutionScope.COMPILE
            && firstAppleFragment != null
            && flowType.platforms.singleOrNull()?.toPlatform() == firstAppleFragment.platform
        ) {
            deduplicateSwiftPMDependenciesAndApplyPlatformConstraints(
                module = this,
            ).map {
                SwiftPMDependencyNodeFromAmperModuleImpl(
                    swiftPMDependency = it.swiftPMDependency,
                    notation =
                        // FIXME: Pass all notations related to a SwiftPM dependency
                        it.notations.first(),
                    templateContext = moduleContext,
                )
            }
        } else emptyList()

        val node = ModuleDependencyNodeWithModuleAndContext(
            module = this,
            isForTests = flowType.isTest,
            children = dependencies + swiftPMDependencies,
            templateContext = moduleContext,
            notation = notation,
            topLevel = directDependencies,
        )

        return node
    }

    /**
     * SwiftPM dependencies are initially duplicated (as regular dependencies) in every fragment. Here we deduplicate
     * them by absolutePath/repository and apply platform constraints if SwiftPM dependency was consumed in a qualified
     * fragment.
     */
    private class SwiftPMDependencyWithNotations(
        val swiftPMDependency: SwiftPMDependency,
        val notations: Set<SwiftPMDependencyNotation>,
    )
    private fun deduplicateSwiftPMDependenciesAndApplyPlatformConstraints(
        module: AmperModule
    ): List<SwiftPMDependencyWithNotations> {
        class SwiftPMDependencyPlatformsAndNotations(
            val platforms: Set<SwiftPMDependency.Platform>,
            val notations: Set<SwiftPMDependencyNotation>,
        )

        val appleFragments = module.fragments.filter { it.platforms.all { it.isDescendantOf(Platform.APPLE) } }
        val modulePlatformConstraints = appleFragments.flatMap { it.platforms }.map { it.toSwiftPMPlatform() }.toSet()
        val platformsByDeclaredSwiftPMDependency = mutableMapOf<SwiftPMDependency, SwiftPMDependencyPlatformsAndNotations>()
        // Deduplicate SwiftPM dependencies coming from notation
        appleFragments.forEach { appleFragment ->
            val fragmentSwiftPMPlatforms = appleFragment.platforms.map { it.toSwiftPMPlatform() }.toSet()
            appleFragment.externalDependencies.forEach { notation ->
                val dependency = when (notation) {
                    is LocalSwiftPMDependencyNotation -> notation.swiftPMDependency
                    is RemoteSwiftPMDependencyNotation -> notation.swiftPMDependency
                    is DefaultScopedNotation,
                    is BomDependency,
                    is MavenDependency -> return@forEach
                }
                val existing = platformsByDeclaredSwiftPMDependency[dependency]
                if (existing != null) {
                    val right = fragmentSwiftPMPlatforms - existing.platforms
                    if (right.isNotEmpty()) {
                        platformsByDeclaredSwiftPMDependency[dependency] = SwiftPMDependencyPlatformsAndNotations(
                            platforms = right,
                            notations = existing.notations + notation,
                        )
                    }
                } else {
                    platformsByDeclaredSwiftPMDependency[dependency] = SwiftPMDependencyPlatformsAndNotations(
                        platforms = fragmentSwiftPMPlatforms,
                        notations = setOf(notation),
                    )
                }
            }
        }

        val swiftPMDependencyByIdentifier = mutableMapOf<Any, SwiftPMDependencyWithNotations>()
        platformsByDeclaredSwiftPMDependency.toList().forEach {
            val swiftPMDependency = it.first
            val platformsAndNotations = it.second
            val id = when (swiftPMDependency) {
                is SwiftPMDependency.Local -> swiftPMDependency.absolutePath
                is SwiftPMDependency.Remote -> swiftPMDependency.repository to swiftPMDependency.version
            }
            val effectivePlatformConstraints = if (platformsAndNotations.platforms == modulePlatformConstraints) {
                null
            } else {
                platformsAndNotations.platforms
            }

            val existingProducts = swiftPMDependencyByIdentifier[id]?.swiftPMDependency?.products ?: emptyList()
            val existingNotations = swiftPMDependencyByIdentifier[id]?.notations ?: emptySet()
            val combinedProductsList = existingProducts + swiftPMDependency.products.map {
                it.copy(
                    platformConstraints = effectivePlatformConstraints?.toList()
                )
            }

            swiftPMDependencyByIdentifier[id] = SwiftPMDependencyWithNotations(
                swiftPMDependency = when (swiftPMDependency) {
                    is SwiftPMDependency.Local -> swiftPMDependency.copy(products = combinedProductsList)
                    is SwiftPMDependency.Remote -> swiftPMDependency.copy(products = combinedProductsList)
                },
                notations = existingNotations + platformsAndNotations.notations,
            )
        }

        return swiftPMDependencyByIdentifier.values.toList()
    }

    private fun Fragment.toDependencyNode(
        platforms: Set<ResolutionPlatform>,
        directDependencies: Boolean,
        moduleContext: Context,
        visitedModules: MutableSet<AmperModule>,
        resolutionSettings: AmperResolutionSettings,
        sharedResolutionCache: Cache,
    ): List<DependencyNodeHolderWithNotationAndContext> {
        val fragmentDependencies = externalDependencies
            .distinct()
            .mapNotNull { dependency ->
                when (dependency) {
                    is MavenDependencyBase -> {
                        val includeDependency = dependency.belongsToClasspath(platforms, directDependencies)
                        if (includeDependency) {
                            dependency.toFragmentDirectDependencyNode(this, directDependencies, moduleContext)
                        } else null
                    }

                    is LocalModuleDependency -> {
                        val resolvedDependencyModule = dependency.module
                        if (!visitedModules.contains(resolvedDependencyModule)) {
                            val includeDependency = dependency.belongsToClasspath(platforms, directDependencies)
                            if (includeDependency) {
                                resolvedDependencyModule.fragmentsModuleDependencies(
                                    directDependencies = false, notation = dependency, visitedModules = visitedModules,
                                    resolutionSettings = resolutionSettings,
                                    sharedResolutionCache = sharedResolutionCache
                                )
                            } else null
                        } else null
                    }
                    /**
                     * These are handled separately in [deduplicateSwiftPMDependenciesAndApplyPlatformConstraints]
                     */
                    is LocalSwiftPMDependencyNotation,
                    is RemoteSwiftPMDependencyNotation -> null

                    is DefaultScopedNotation -> error(
                        "Unsupported dependency type: '$dependency' " +
                                "at module '${module.userReadableName}' fragment '${name}'"
                    )
                }
            }

        return fragmentDependencies
    }

    fun Notation.belongsToClasspath(
        platforms: Set<ResolutionPlatform>,
        directDependencies: Boolean,
    ): Boolean {
        return when(this) {
            is DefaultScopedNotation -> {
                shouldBeAddedByNotion(platforms, directDependencies)
            }
            is BomDependency -> {
                when (flowType.scope) {
                    // BOM affects the compilation classpath of the module where it is declared,
                    // including exported direct dependencies
                    ResolutionScope.COMPILE -> true
                    // BOM affects the runtime classpath of the module and all its consumers
                    ResolutionScope.RUNTIME -> true
                }
            }
            is LocalSwiftPMDependencyNotation,
            is RemoteSwiftPMDependencyNotation -> true
        }
    }

    private fun DefaultScopedNotation.shouldBeAddedByNotion(
        platforms: Set<ResolutionPlatform>,
        directDependencies: Boolean,
    ): Boolean =
        when (flowType.scope) {
            // the compilation classpath graph contains direct and exported transitive dependencies,
            // for native platforms,
            // the compilation classpath graph contains all transitive none-exported dependencies as well,
            // because native compilation (and linking) depends on entire transitive dependencies.
            // runtime-only dependencies are not included in the compilation classpath graph
            ResolutionScope.COMPILE -> compile && (directDependencies || exported || (flowType.includeNonExportedNative && platforms.all { it.nativeTarget != null } ))
            ResolutionScope.RUNTIME -> runtime
        }

    /**
     * Returns all fragments in this module that target the given [platforms].
     */
    private fun Collection<Fragment>.sortedForClasspath(platforms: Set<Platform>): List<Fragment> =
        this
            .sortedBy { it.name }
            .ensureFirstFragment(platforms)

    private fun List<Fragment>.ensureFirstFragment(platforms: Set<Platform>) =
        if (this.isEmpty() || this[0].platforms == platforms)
            this
        else {
            val fragmentWithPlatform = this.firstOrNull { it.platforms == platforms }
            if (fragmentWithPlatform == null) {
                this
            } else
                buildList {
                    add(fragmentWithPlatform)
                    addAll(this@ensureFirstFragment - fragmentWithPlatform)
                }
        }
}

/**
 * Returns the list of dependencies this [Fragment] add to the classpath.
 * The classpath is defined by the following parameters
 * [scope], [platforms], [directDependencies] and [includeNonExportedNative]
 *
 * @param platforms a set of platforms resolution is made for. It might be different from the set of own [Fragment]
 * platforms (in this case, [platforms] is a subset of this [Fragment.platforms]).
 * @param scope resolution scope to form a classpath for
 * @param directDependencies should be set to true if a caller is interested in the dependencies
 * required for this particular fragment to be compiled or run. If that fragment is a nested dependency of some
 * larger resolution scope, then the value should be set to false
 * (in this case, for instance, non-exported COMPILE dependencies of the fragment
 * won't be added to the resulting list, but only exported ones)
 *  @param includeNonExportedNative Default value is true. It specifies if transitive COMPILE dependencies should
 *  be included in the classpath for the native compilation (i.e., [platforms] contains native platforms only)
 */
private fun Fragment.classpath(
    directDependencies: Boolean,
    scope: ResolutionScope,
    platforms: Set<ResolutionPlatform>,
    includeNonExportedNative: Boolean = true,
): List<Notation> {
    check(this.platforms.map { it.toResolutionPlatform() }.toSet().containsAll(platforms)) {
        "Given set of platforms $platforms must be a subset of the Fragment.platforms ${this.platforms}"
    }
    val classpath = Classpath(DependenciesFlowType.ClassPathType(
        scope, platforms, isTest, includeNonExportedNative
    ))
    return with (classpath) {
        externalDependencies.filter {
            it.belongsToClasspath(
                platforms,
                directDependencies,
            )
        }
    }
}

/**
 * Returns a subset of dependencies of this [Fragment] that should be added
 * to a classpath of another [Fragment] from a different module that depends on this one.
 *
 * The consumer classpath is defined by the following parameters
 * [scope], [platforms] and [includeNonExportedNative]
 *
 * @param platforms a set of platforms resolution is made for. It might be different from the set of own [Fragment]
 *  platforms (in this case, [platforms] is a subset of this [Fragment.platforms]).
 *
 * @param scope resolution scope to form a classpath for
 *
 * @param includeNonExportedNative Default value is true. It specifies if transitive COMPILE dependencies should
 *  be included in the classpath for the native compilation (i.e., [platforms] contains native platforms only)
 */
fun Fragment.dependenciesAvailableForConsumerClasspath(
    scope: ResolutionScope,
    platforms: Set<ResolutionPlatform>,
    includeNonExportedNative: Boolean = true,
): List<Notation> = classpath(
    directDependencies = false,
    scope = scope, platforms = platforms, includeNonExportedNative = includeNonExportedNative
)
