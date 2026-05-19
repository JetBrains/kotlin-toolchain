/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("ConvertCallChainIntoSequence")

package org.jetbrains.amper.tasks

import kotlinx.serialization.Serializable
import org.jetbrains.amper.CliReportingMavenResolver
import org.jetbrains.amper.cli.logging.withoutConsoleLogging
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.telemetry.setFragments
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.DefaultScopedNotation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.dr.resolver.DirectFragmentDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.dr.resolver.flow.toPlatform
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.dr.resolver.getExternalDependencies
import org.jetbrains.amper.frontend.fragmentsTargeting
import org.jetbrains.amper.frontend.mavenResolveRepositories
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.ResultWithSerializable
import org.jetbrains.amper.incrementalcache.execute
import org.jetbrains.amper.maven.publish.PublicationCoordinatesOverride
import org.jetbrains.amper.maven.publish.PublicationCoordinatesOverrides
import org.jetbrains.amper.serialization.paths.SerializablePath
import org.jetbrains.amper.tasks.CommonTaskUtils.userReadableList
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import kotlin.io.path.pathString
import kotlin.io.path.relativeToOrSelf
import kotlin.time.Instant

internal data class ExternalDependenciesResolutionResult(
    val compileDependenciesRootNode: DependencyNode,
    val runtimeDependenciesRootNode: DependencyNode?,
    val expirationTime: Instant? = null,
)

/**
 * Enforces resolution contract for compile/runtime dependencies (that they must be resolved together).
 * 
 * Also, hides cache entry key creation details from the caller, requesting [moduleDependencies], [platform] and [isTest] instead.
 * 
 * This function should be used only if some task requires an actual graph instead of
 * a plain list of dependencies. It reuses the same cache that [ResolveExternalDependenciesTask],
 * so that in general graph will be deserialized from the cache entry.
 */
internal suspend fun CliReportingMavenResolver.doResolveExternalDependencies(
    platform: Platform,
    isTest: Boolean,
    moduleDependencies: ModuleDependencies,
): ExternalDependenciesResolutionResult {
    val resolveSourceMoniker = "module ${moduleDependencies.module.userReadableName}"

    val allLeafPlatformsGraph = moduleDependencies.allLeafPlatformsGraph(isTest)
    val platformCompileDepsIndex = allLeafPlatformsGraph.children.indexOfFirst {
        it.context.settings.platforms.single() == platform.toResolutionPlatform()
                && it.context.settings.scope == ResolutionScope.COMPILE
    }.takeIf { it != -1 } ?: error("Compile dependencies for $platform are not found")

    val platformRuntimeDepsIndex = allLeafPlatformsGraph.children.indexOfFirst {
        it.context.settings.platforms.single() == platform.toResolutionPlatform()
                && it.context.settings.scope == ResolutionScope.RUNTIME
    }.takeIf { it != -1 }

    val resolvedGraph = resolve(moduleDependencies = moduleDependencies, isTest = isTest, resolveSourceMoniker = resolveSourceMoniker)
    val resolvedChildren = resolvedGraph.root.children
    return ExternalDependenciesResolutionResult(
        resolvedChildren[platformCompileDepsIndex],
        platformRuntimeDepsIndex?.let { resolvedChildren[it] },
        resolvedGraph.expirationTime
    )
}

class ResolveExternalDependenciesTask(
    private val module: AmperModule,
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
    private val platform: Platform,
    private val isTest: Boolean,
    private val moduleDependencies: ModuleDependencies,
    override val taskName: TaskName,
) : Task {

    private val mavenResolver by lazy {
        CliReportingMavenResolver(userCacheRoot, incrementalCache)
    }

    /**
     * An empty result that is returned when some error in
     * the task parameters occurred (not supported platform, for instance).
     */
    private val onParametersErrorResult = Result(
        compileClasspath = emptyList(),
        runtimeClasspath = emptyList(),
        coordinateOverridesForPublishing = PublicationCoordinatesOverrides(),
    )

    override suspend fun run(
        dependenciesResult: List<TaskResult>,
        executionContext: TaskGraphExecutionContext,
    ): TaskResult {
        // order in compileDependencies is important (classpath is generally (and unfortunately!) order-dependent),
        // but the current implementation requires a full review of it

        val resolutionPlatform = platform.toResolutionPlatform()
        if (resolutionPlatform == null) {
            logger.error("${module.userReadableName}: Non-leaf platform $platform is not supported for resolving external dependencies")
            return onParametersErrorResult
        } else if (resolutionPlatform != ResolutionPlatform.JVM
            && resolutionPlatform != ResolutionPlatform.ANDROID
            && resolutionPlatform.nativeTarget == null
            && resolutionPlatform != ResolutionPlatform.JS
            && resolutionPlatform.wasmTarget == null
        ) {
            logger.error("${module.userReadableName}: $platform is not yet supported for resolving external dependencies")
            return onParametersErrorResult
        }

        return resolveModuleDependencies(
            resolutionPlatform,
            userCacheRoot,
            incrementalCache,
            isTest,
            moduleDependencies,
            mavenResolver
        )
    }

    @Serializable
    class Result(
        val compileClasspath: List<SerializablePath>,
        val runtimeClasspath: List<SerializablePath>,
        val coordinateOverridesForPublishing: PublicationCoordinatesOverrides,
    ) : TaskResult

    companion object {
        private val logger = LoggerFactory.getLogger(ResolveExternalDependenciesTask::class.java)

        internal suspend fun resolveModuleDependencies(
            resolutionPlatform: ResolutionPlatform,
            userCacheRoot: AmperUserCacheRoot,
            incrementalCache: IncrementalCache,
            isTest: Boolean,
            moduleDependencies: ModuleDependencies,
            mavenResolver: CliReportingMavenResolver
        ): Result {
            val module = moduleDependencies.module
            val repositories = module.mavenResolveRepositories
                .map { it.url }
                .distinct()

            val fragments = module.fragmentsTargeting(resolutionPlatform.toPlatform(), isTest)

            val platformOnlyDependencies = moduleDependencies.forPlatform(resolutionPlatform.toPlatform(), isTest)

            return spanBuilder("resolve-dependencies")
                .setAmperModule(moduleDependencies.module)
                .setFragments(fragments)
                .setListAttribute("dependencies", platformOnlyDependencies.compileDepsCoordinates.map { it.toString() })
                .setListAttribute(
                    "runtimeDependencies",
                    platformOnlyDependencies.runtimeDepsCoordinates.map { it.toString() })
                .setAttribute("isTest", isTest)
                .setAttribute("platform", resolutionPlatform.type.value)
                .also {
                    resolutionPlatform.nativeTarget?.let { target ->
                        it.setAttribute("native-target", target)
                    }
                    resolutionPlatform.wasmTarget?.let { target ->
                        it.setAttribute("wasm-target", target)
                    }
                }
                .use {
                    logger.debug(
                        "resolve dependencies ${module.userReadableName} -- " +
                                "${fragments.userReadableList()} -- " +
                                "${platformOnlyDependencies.compileDepsCoordinates.joinToString(" ")} -- " +
                                "resolvePlatform=${resolutionPlatform.type.value} " +
                                "nativeTarget=${resolutionPlatform.nativeTarget} " +
                                "wasmTarget=${resolutionPlatform.wasmTarget}"
                    )

                    val cacheEntryKey = "${module.userReadableName}:${resolutionPlatform.pretty}:${if (isTest) "test" else "main"}"

                    val result = try {
                        val moduleDependenciesRoot = moduleDependencies.allLeafPlatformsGraph(isTest)
                        incrementalCache.execute(
                            key = cacheEntryKey,
                            inputValues = mapOf(
                                "userCacheRoot" to userCacheRoot.path.pathString,
                                "dependencies" to moduleDependenciesRoot.children.flatMap { it.getExternalDependencies() }
                                    .joinToString("|"),
                                "repositories" to repositories.joinToString("|"),
                                "resolvePlatform" to resolutionPlatform.type.value,
                                "resolveNativeTarget" to (resolutionPlatform.nativeTarget ?: ""),
                                "resolveWasmTarget" to (resolutionPlatform.wasmTarget ?: ""),
                            ),
                            inputFiles = emptyList(),
                            serializer = Result.serializer(),
                        ) {
                            val (compileDependenciesRootNode, runtimeDependenciesRootNode, expirationTime) =
                                mavenResolver.doResolveExternalDependencies(
                                    platform = resolutionPlatform.toPlatform(),
                                    isTest = isTest,
                                    moduleDependencies = moduleDependencies,
                                )

                            val compileClasspath = compileDependenciesRootNode.dependencyPaths()
                            val runtimeClasspath = runtimeDependenciesRootNode?.dependencyPaths() ?: emptyList()

                            val publicationCoordsOverrides = getPublicationCoordinatesOverrides(
                                compileDependenciesRootNode = compileDependenciesRootNode,
                                runtimeDependenciesRootNode = runtimeDependenciesRootNode,
                            )

                            ResultWithSerializable(
                                outputFiles = (compileClasspath + runtimeClasspath).toSet().sorted(),
                                // We reuse the task Result class here because it has exactly the fields we need.
                                // If types must diverge, we can always introduce a new Result class for this.
                                outputValue = Result(
                                    compileClasspath = compileClasspath,
                                    runtimeClasspath = runtimeClasspath,
                                    coordinateOverridesForPublishing = publicationCoordsOverrides,
                                ),
                                expirationTime = expirationTime,
                            )
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        withoutConsoleLogging {
                            logger.error(
                                "resolve dependencies of module '${module.userReadableName}' failed\n" +
                                        "fragments: ${fragments.userReadableList()}\n" +
                                        "repositories:\n${repositories.joinToString("\n").prependIndent("  ")}\n" +
                                        "direct dependencies:\n${
                                            platformOnlyDependencies.compileDirectDepsCoordinates
                                                .joinToString("\n")
                                                .prependIndent("  ")
                                        }\n" +
                                        "all dependencies:\n${
                                            platformOnlyDependencies.compileDepsCoordinates
                                                .joinToString("\n")
                                                .prependIndent("  ")
                                        }\n" +
                                        "platform: $resolutionPlatform" +
                                        (resolutionPlatform.nativeTarget?.let { "\nnativeTarget: $it" } ?: "") +
                                        (resolutionPlatform.wasmTarget?.let { "\nwasmTarget: $it" } ?: ""), t)
                        }

                        throw t
                    }

                    logger.debug(
                        "resolve dependencies ${module.userReadableName} -- " +
                                "${fragments.userReadableList()} -- " +
                                "${platformOnlyDependencies.compileDepsCoordinates.joinToString(" ")} -- " +
                                "resolvePlatform=$resolutionPlatform " +
                                "nativeTarget=${resolutionPlatform.nativeTarget}\n" +
                                "wasmTarget=${resolutionPlatform.wasmTarget}\n" +
                                "${repositories.joinToString(" ")} resolved to:\n${
                                    result.outputValue.compileClasspath.joinToString("\n") {
                                        "  " + it.relativeToOrSelf(
                                            userCacheRoot.path
                                        ).pathString
                                    }
                                }"
                    )

                    // todo (AB) : output should contain placeholder for every module (in a correct place in the list!!!
                    // todo (AB) : It might be replaced with the path to compiled module later in order to form complete correctly ordered classpath)
                    result.outputValue
                }
        }

        private fun getPublicationCoordinatesOverrides(
            compileDependenciesRootNode: DependencyNode,
            runtimeDependenciesRootNode: DependencyNode?,
        ): PublicationCoordinatesOverrides {
            val compileOverrides = compileDependenciesRootNode.children.getOverridesForDirectDeps()
            val runtimeOverrides = runtimeDependenciesRootNode
                ?.children
                ?.getOverridesForDirectDeps(directDependencyCondition = { (notation as? DefaultScopedNotation)?.compile == false })
                ?: emptyList()
            return PublicationCoordinatesOverrides(compileOverrides + runtimeOverrides)
        }

        private fun List<DependencyNode>.getOverridesForDirectDeps(
            directDependencyCondition: DirectFragmentDependencyNode.() -> Boolean = { true },
        ): List<PublicationCoordinatesOverride> = this
            .filterIsInstance<DirectFragmentDependencyNode>()
            .filter { it.directDependencyCondition() }
            .mapNotNull { directMavenDependency ->
                val node = directMavenDependency.dependencyNode
                val coordinatesOriginal = node.getOriginalMavenCoordinates()
                val coordinatesForPublishing = node.getMavenCoordinatesForPublishing()
                if (coordinatesOriginal != coordinatesForPublishing) {
                    PublicationCoordinatesOverride(
                        originalCoordinates = coordinatesOriginal,
                        variantCoordinates = coordinatesForPublishing,
                    )
                } else {
                    null
                }
            }
    }
}