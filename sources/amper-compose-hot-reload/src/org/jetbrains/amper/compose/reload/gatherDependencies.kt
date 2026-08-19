/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compose.reload

import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.CompositeVersionCatalog
import org.jetbrains.amper.frontend.FileVersionCatalog
import org.jetbrains.amper.frontend.InMemoryVersionCatalog
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.VersionCatalog
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.fragmentsTargeting
import java.nio.file.Path
import kotlin.io.path.div

/**
 * Gathers all the paths that Compose Hot Reload of the given module depends on.
 * Only the *build/execution* dependencies are included here.
 *
 * To get dependencies of the model itself use [modelDependencies].
 */
context(context: HotReloadProjectContext)
internal fun AmperModule.jvmComposeRunDependencies(): Set<Path> = buildSet {
    val relevantModules = buildSet {
        fun addDependencies(scope: ResolutionScope) = addAll(
            with(ModuleDependencies) {
                getLocalModuleDependencies(
                    isTest = false,
                    platform = Platform.JVM,
                    dependencyReason = scope,
                    userCacheRoot = context.userCacheRoot,
                    incrementalCache = context.incrementalCache,
                    openTelemetry = context.openTelemetry,
                )
            }
        )
        add(this@jvmComposeRunDependencies)
        addDependencies(ResolutionScope.COMPILE)  // Needed for re-compilation
        addDependencies(ResolutionScope.RUNTIME)  // Needed because we actually reload a runtime
    }

    relevantModules.forEach { module ->
        module.fragmentsTargeting(Platform.JVM, isTest = false).forEach { fragment ->
            addAll(fragment.sourceRoots)
            add(fragment.resourcesPath)
            add(fragment.composeResourcesPath)
        }
    }

    // TODO: Plugins.
}

/**
 * Gathers all the model dependencies (not specific for Compose Hot Reload).
 * We want to reload the model on every relevant change.
 *
 * If the model change ends up not affecting the relevant hot reload run,
 * then the build will take everything from the cache, and the reload will not actually happen.
 */
internal fun Model.modelDependencies(): Set<Path> = buildSet {
    // Project root
    add(projectRoot / "project.yaml")

    modules.forEach { module ->
        // module.yaml
        add(module.source.buildFile)

        // included templates
        module.usedTemplates.forEach { template ->
            add(template.toNioPath())
        }

        // catalog
        addAll(module.usedCatalog.modelDependencies())
    }

    amperPlugins.forEach { plugin ->
        add(plugin.pluginModule.source.buildFile / "plugin.yaml")
    }
}

private fun VersionCatalog.modelDependencies(): Set<Path> = buildSet {
    when (this@modelDependencies) {
        is CompositeVersionCatalog -> catalogs.forEach { addAll(it.modelDependencies()) }
        is FileVersionCatalog -> add(location.toNioPath())
        is InMemoryVersionCatalog -> Unit  // Nothing here
    }
}