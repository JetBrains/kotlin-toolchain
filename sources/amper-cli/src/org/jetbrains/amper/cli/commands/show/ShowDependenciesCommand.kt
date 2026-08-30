/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands.show

import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.choice
import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.cli.commands.AmperModelAwareCommand
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.options.AllModulesOptionName
import org.jetbrains.amper.cli.options.ModuleFilter
import org.jetbrains.amper.cli.options.PlatformGroup
import org.jetbrains.amper.cli.options.PlatformGroupOption
import org.jetbrains.amper.cli.options.moduleFilter
import org.jetbrains.amper.cli.options.platformGroupOption
import org.jetbrains.amper.cli.options.selectModules
import org.jetbrains.amper.cli.options.validLeavesIn
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.ResolvedGraph
import org.jetbrains.amper.dependency.resolution.filterGraph
import org.jetbrains.amper.dependency.resolution.mavenCoordinatesTrimmed
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.ModuleResolutionFilter
import org.jetbrains.amper.frontend.dr.resolver.ResolutionType
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform

internal class ShowDependenciesCommand: AmperModelAwareCommand(name = "dependencies") {

    private val moduleFilter by moduleFilter(
        moduleOptionHelp = """
            The module to show the dependencies of (run the `show modules` command to get the modules list).
            This option can be repeated to show the dependencies of several modules.
            If unspecified, you will be prompted to choose a module.

            See also `$AllModulesOptionName` if you want to show dependencies for all modules in the project.
        """.trimIndent(),
        allModulesOptionHelp = "Show the dependencies of all modules.",
    )

    private val platformGroups by platformGroupOption(
        help = """
            The name of a platform, group of platforms, or alias to show the 
            dependencies of.

            For example, `$PlatformGroupOption=native` shows the dependencies resolved for the sources that target
            all native platforms declared in the module: `src` and `src@native`.

            This option can be repeated to show the dependencies used in multiple resolution scopes.
            By default, the dependencies for all resolution scopes of the module are shown.
        """.trimIndent(),
    ).multiple().unique()

    private val includeTests by option("--include-tests",
        help = "Whether to include information about test dependencies or not, false by default"
    ).flag("--exclude-tests", default = false)

    private val filter by option("--filter",
        metavar = "groupId:artifactId",
        help = "Filter the dependency graph to only show paths that contain a specific dependency, in any version. " +
                "Only maven dependencies are supported at the moment, in the format `groupId:artifactId`. " +
                "If a dependency version is resolved based on a dependency constraint, the path from the root to " +
                "that constraint will be included in the resulting subgraph as well. "
    )

    private val scopes by option("--scope",
        help = """
            The scope for which to show the graph. If unspecified, both graphs will be shown.
            This option can be repeated to show the dependencies for multiple scopes.
        """.trimIndent()
    )
        .choice(
            "compile" to ResolutionScope.COMPILE,
            "runtime" to ResolutionScope.RUNTIME,
        )
        .multiple(default = listOf(ResolutionScope.COMPILE, ResolutionScope.RUNTIME))

    override fun help(context: com.github.ajalt.clikt.core.Context): String = "Print the resolved dependencies graph of the module"

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        val selectedModules = moduleFilter.selectModules(projectModules = model.modules)
        selectedModules.forEach { selectedModule ->
            printModuleDependencies(
                module = selectedModule, cliContext = cliContext,
                // When using --all-modules, the user most likely just wants to filter "all" dependencies based on the
                // platforms and is ok ignoring some modules that don't have these platforms.
                failOnUnsupportedPlatforms = moduleFilter !is ModuleFilter.All,
            )
        }
    }

    private suspend fun printModuleDependencies(module: AmperModule, cliContext: ProjectCliContext, failOnUnsupportedPlatforms: Boolean) {
        val platformSetsToResolveFor = if (platformGroups.isEmpty()) {
            module.fragments.map { it.platforms }.distinct()
        } else {
            platformGroups
                .map { it.checkAndFilterLeaves(module, failOnUnsupportedPlatforms) }
                .filter { it.isNotEmpty() } // we might have empty sets when we don't report errors, just skip
                .distinct()
                .ifEmpty { return } // if none of the sets had any platforms, just skip
        }

        val mavenCoordinates = filter?.resolveFilter()

        val resolvedModuleDependencies = module.resolveDependencies(platformSetsToResolveFor, cliContext)

        printDependencies(
            mavenCoordinates,
            module,
            // printing requested nodes only
            resolvedModuleDependencies, filter)
    }

    private suspend fun AmperModule.resolveDependencies(
        platformSetsToResolveFor: List<Set<Platform>>,
        cliContext: ProjectCliContext
    ): List<DependencyNode> {
        val resolutionPlatformSetsToResolveFor = platformSetsToResolveFor.map { it.mapNotNull { it.toResolutionPlatform() }.toSet() }

        val resolvedGraph: ResolvedGraph =
            // todo (AB) : [AMPER-4905] Pass all modules at once (though prettyPrint works for a single resolved graph,
            //  but not for many combined graphs, see [isConstraintAffectingTheGraph])
            ModuleDependencies
                .resolveModuleDependencies(
                    modules = listOf(this@resolveDependencies),
                    resolutionSettings = AmperResolutionSettings(
                        userCacheRoot = commonOptions.sharedCachesRoot,
                        incrementalCache = cliContext.incrementalCache,
                        openTelemetry = GlobalOpenTelemetry.get(),
                    ),
                    // Filtering by scope and platforms is done later
                    filter = ModuleResolutionFilter(
                        resolutionType = if (includeTests) ResolutionType.ALL else ResolutionType.MAIN,
                        // Filtering by scope and platforms is done later below
                        scope = null, platforms = null,
                    )
                )

        return resolutionPlatformSetsToResolveFor
            .flatMap { platforms ->
                resolvedGraph.root.children.filter {
                    it is ModuleDependencyNode
                            && platforms == it.resolutionConfig.platforms
                            && scopes.contains(it.resolutionConfig.scope)
                }
            }.distinctBy { it.graphEntryName } // todo (AB) : Why is it needed
    }

    private fun printDependencies(
        mavenCoordinates: MavenCoordinates?,
        resolvedModule: AmperModule,
        rootNodesToPrint: List<DependencyNode>,
        filter: String?,
    ) {
        if (mavenCoordinates == null) {
            terminal.println("Dependencies of module ${resolvedModule.userReadableName}: \n")
            rootNodesToPrint.forEach {
                terminal.println(it.prettyPrint())
            }
        } else {
            // todo (AB) : [AMPER-4905] If module graph is filtered, a resolved version of dependency might come
            //  from filtered out fragment and thus resulting graph will have no information on where resolved version comes from
            //  It might be improved in several ways:
            //  - we might invert graph the same as Idea plugin does, starting from given mavenCoordinates, showing paths
            //    where those coordinates comes from (including other filtered out fragments in case resolved version comes from them)
            //  - we might deny platforms and scope filtering for given mavenCoordinates. In that case there will be at least one
            //    fragment that shows resolved version of the dependency
            val rootsToPrint = rootNodesToPrint.mapNotNull { rootNode ->
                rootNode.filterGraph(mavenCoordinates.groupId, mavenCoordinates.artifactId)
                    .takeIf { it.children.isNotEmpty() }
            }
            if (rootsToPrint.isEmpty()) {
                terminal.println("Module doesn't depend on $filter")
            } else {
                terminal.println("Subgraphs of module dependencies showing where dependency on the library '$filter' comes from: \n")
                rootsToPrint.forEach {
                    terminal.println(it.prettyPrint())
                }
            }
        }
    }

    private fun String.resolveFilter(): MavenCoordinates {
        val parts = this.split(":")
        if (parts.size != 2) userReadableError("Option 'filter' supports maven coordinates in the format 'group:module' only.")
        return mavenCoordinatesTrimmed(groupId = parts[0], artifactId = parts[1], version = null)
    }

    /**
     * Returns the leaf platforms from this group that are present in the given [module]'s declared platforms.
     *
     * If any explicit leaf platform from this group is not declared in the [module], it is reported as an error.
     * If any intermediate platform from this group doesn't intersect with the [module] leaf platforms at all, it is
     * reported as an error.
     */
    private fun PlatformGroup.checkAndFilterLeaves(
        module: AmperModule,
        failOnUnsupportedPlatforms: Boolean,
    ): Set<Platform> {
        val validLeafPlatforms = validLeavesIn(module)
        if (validLeafPlatforms.isEmpty() && failOnUnsupportedPlatforms) {
            // can never happen with an alias, so the wording is ok like this
            userReadableError("Module '${module.userReadableName}' doesn't support platform '$name'")
        }
        return validLeafPlatforms
    }
}
