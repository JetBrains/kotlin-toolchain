/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.CliReportingMavenResolver
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.JavaVersion
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.MavenCoordinatesExt
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.toRepository
import org.jetbrains.amper.frontend.dr.resolver.toDrMavenCoordinates
import org.jetbrains.amper.frontend.jdkSettings
import org.jetbrains.amper.frontend.mavenResolveRepositories
import org.jetbrains.amper.frontend.schema.UnscopedBomDependency
import org.jetbrains.amper.frontend.schema.UnscopedDependency
import org.jetbrains.amper.frontend.schema.UnscopedExternalMavenDependency
import org.jetbrains.amper.frontend.schema.UnscopedModuleDependency
import org.jetbrains.amper.frontend.schema.coordinates
import org.jetbrains.amper.frontend.schema.toMavenCoordinates
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import java.nio.file.Path
import kotlin.io.path.pathString

// TODO merge with regular resolveDependencies task?
//  Hint: Move platform, scope and coordinates out as abstract members.
internal abstract class AbstractResolveJvmExternalDependenciesTask(
    private val module: AmperModule,
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
    private val resolutionMonikerPrefix: String,
): Task {
    private val mavenResolver = CliReportingMavenResolver(userCacheRoot, incrementalCache)

    protected abstract fun getMavenCoordinatesToResolve(): List<UnscopedDependency>

    protected open val incrementalCacheKey: String get() = taskName.name

    override suspend fun run(dependenciesResult: List<TaskResult>, executionContext: TaskGraphExecutionContext): TaskResult {
        val repositories = module.mavenResolveRepositories.map { it.toRepository() }.distinct()
        
        val externalUnscopedDependencies = getMavenCoordinatesToResolve()
            .filter { it !is UnscopedModuleDependency }
            .ifEmpty { return Result(emptyList()) }

        val configuration = mapOf(
            "userCacheRoot" to userCacheRoot.path.pathString,
            "repositories" to repositories.joinToString("|"),
            "dependencies-coordinates" to externalUnscopedDependencies.map { when(it) {
                is UnscopedExternalMavenDependency -> it.coordinates
                // Here we can safely cast, since catalog references were replaced.
                is UnscopedBomDependency -> "bom: ${(it.bom as UnscopedExternalMavenDependency).coordinates}"
                else -> error("Unexpected dependency type: ${it::class.qualifiedName}")
            } }.joinToString("|"),
        )
        val resolvedExternalJars = incrementalCache.execute(
            incrementalCacheKey,
            configuration,
            emptyList(),
        ) {
            spanBuilder(taskName.name)
                .setAmperModule(module)
                .setListAttribute("dependencies-coordinates", externalUnscopedDependencies.map {
                    when(it) {
                        is UnscopedExternalMavenDependency -> it.coordinates
                        // Here we can safely cast, since catalog references were replaced.
                        is UnscopedBomDependency -> "bom: ${(it.bom as UnscopedExternalMavenDependency).coordinates}"
                        else -> error("Unexpected dependency type: ${it::class.qualifiedName}")
                    }
                })
                .use {
                    val externalDependenciesCoordinates = externalUnscopedDependencies.map {
                        val (dependencyCoordinates, isBom) = when(it) {
                            is UnscopedExternalMavenDependency -> it to false
                            is UnscopedBomDependency -> it.bom as UnscopedExternalMavenDependency to true
                            else -> error("Unexpected dependency type: ${it::class.qualifiedName}")
                        }
                        MavenCoordinatesExt(dependencyCoordinates.toMavenCoordinates().toDrMavenCoordinates(), isBom)
                    }

                    mavenResolver.resolveBomAware(
                        repositories,
                        ResolutionScope.RUNTIME,
                        ResolutionPlatform.JVM,
                        "$resolutionMonikerPrefix${module.userReadableName}-${Platform.JVM.pretty}",
                        jvmRelease = JavaVersion(module.jdkSettings.version),
                        mavenCoordinates = externalDependenciesCoordinates,
                    ).toIncrementalCacheResult()
                }
        }.outputFiles

        return Result(resolvedExternalJars)
    }

    class Result(val externalJars: List<Path>) : TaskResult
}
