/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.amper.CliReportingMavenResolver
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.swiftpm.SwiftPMDependenciesMetadataNode
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.MavenResolver
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.moduleDependencies
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencyNode
import org.jetbrains.amper.frontend.dr.resolver.ResolutionDepth
import org.jetbrains.amper.frontend.dr.resolver.swiftpm.SwiftPMDependencyNodeFromAmperModule
import org.jetbrains.amper.frontend.schema.swiftpm.SwiftPMDependency
import org.jetbrains.amper.tasks.ModuleSequenceCtx
import org.jetbrains.amper.tasks.ProjectTasksBuilder
import org.jetbrains.amper.tasks.native.leafAppleFragments
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.IOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.MACOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.TVOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.WATCHOS_DEPLOYMENT_TARGET_DEFAULT
import kotlin.io.path.inputStream

context(builder: ProjectTasksBuilder)
fun ModuleSequenceCtx.transitiveSwiftPMDependenciesResolver(): TransitiveSwiftPMDependenciesResolver {
    return TransitiveSwiftPMDependenciesResolver(
        amperModules = builder.model.modules,
        mavenResolver = CliReportingMavenResolver(
            builder.context.userCacheRoot,
            builder.context.incrementalCache,
        ),
        declaredSwiftPMDependencies = module.moduleDependencies(
            AmperResolutionSettings(
                builder.context.userCacheRoot,
                builder.context.incrementalCache,
                GlobalOpenTelemetry.get(),
                includeSwiftPMDependencies = true,
                // Force transitive native resolution since SwiftPM dependencies are also link-time
                includeNonExportedNative = true,
            )
        ),
    )
}

@Serializable
data class SwiftPMImportMetadata(
    val konanTargets: Set<String>,
    val iosDeploymentVersion: String?,
    val macosDeploymentVersion: String?,
    val watchosDeploymentVersion: String?,
    val tvosDeploymentVersion: String?,
    @Suppress("unused")
    val isModulesDiscoveryEnabled: Boolean,
    val dependencies: Set<SwiftPMDependency>,
)

@Serializable
data class SwiftPMDependencies(
    val directSwiftPMDependencies: Set<SwiftPMDependency>,
    val transitiveSwiftPMDependencies: TransitiveSwiftPMMetadata,
) {
    val hasDirectOrTransitiveSwiftPMDependencies
        get() = directSwiftPMDependencies.isNotEmpty() || transitiveSwiftPMDependencies.metadataByDependencyIdentifier.isNotEmpty()
}

@Serializable
data class TransitiveSwiftPMMetadata(
    val metadataByDependencyIdentifier: Map<SwiftPMDependencyIdentifier, SwiftPMImportMetadata>
)

class TransitiveSwiftPMDependenciesResolver(
    private val amperModules: List<AmperModule>,
    private val mavenResolver: MavenResolver,
    private val declaredSwiftPMDependencies: ModuleDependencies,
) {
    private val swiftPMMetadataByAmperModuleName: Map<String, SwiftPMImportMetadata> by lazy {
        val amperModuleByName = amperModules.associateBy { it.userReadableName }
        amperModuleByName.mapValues {
            SwiftPMImportMetadata(
                /**
                 * FIXME: These are necessary for implicit constraints, e.g. an ios-only module consumes SwiftPM
                 * dependency and this module is then consumed by the module with macOS targets. Finish implementing
                 * these.
                 *
                 * These also need to be in sync with Gradle implementation where we use KonanTarget names.
                 */
                konanTargets = amperModuleByName[it.key]!!.leafAppleFragments().map { it.platform.toString() }.toSet(),
                /**
                 * In Gradle implementation these versions are specified in the DSL, published by the project and
                 * are eventually consumed. We agreed to hardcode defaults for now and decide what to do there later:
                 * - We could derive these from the Xcode project as they should be aligned
                 * - Or we could introduce a DSL to tweak these and then we have to do something here
                 */
                iosDeploymentVersion = IOS_DEPLOYMENT_TARGET_DEFAULT,
                macosDeploymentVersion = MACOS_DEPLOYMENT_TARGET_DEFAULT,
                watchosDeploymentVersion = WATCHOS_DEPLOYMENT_TARGET_DEFAULT,
                tvosDeploymentVersion = TVOS_DEPLOYMENT_TARGET_DEFAULT,
                isModulesDiscoveryEnabled = true,
                // We patch it below
                emptySet()
            )
        }
    }

    suspend fun resolve(): SwiftPMDependencies {
        val resolutionResult = mavenResolver.resolve(
            moduleDependencies = declaredSwiftPMDependencies,
            isTest = false,
            leafPlatformsOnly = false,
            resolutionDepth = ResolutionDepth.GRAPH_FULL,
        ).root.distinctBfsSequence()

        /**
         * FIXME: These sets are still necessary for resolution result deduplication
         */
        val directSwiftPMDependencies = mutableSetOf<SwiftPMDependency>()
        val transitiveAmperModuleSwiftPMDependencies = mutableMapOf<String, MutableSet<SwiftPMDependency>>()
        val transitiveMavenSwiftPMDependencies = mutableMapOf<SwiftPMDependencyIdentifier, SwiftPMImportMetadata>()

        resolutionResult.forEach { node ->
            when (node) {
                /**
                 * FIXME: We need resolution to know what are the actual dependencies between AmperModule, but this is
                 * super ugly: we get dependencies from resolution, but then reach out into AmperModules for metadata.
                 *
                 * Maybe we can do both using either resolution or by reaching out into AmperModules.
                 *
                 * E.g. we can just look at [ModuleDependencyNode] and gather [SwiftPMDependencyNodeFromAmperModule] (or [SwiftPMImportMetadata] itself)
                 * from it.
                 */
                is SwiftPMDependencyNodeFromAmperModule -> {
                    val isSelfDependency = node.parents.any { it is ModuleDependencyNode && it.topLevel }
                    if (isSelfDependency) {
                        directSwiftPMDependencies.add(node.swiftPMDependency)
                    } else {
                        transitiveAmperModuleSwiftPMDependencies.getOrPut(node.amperModuleName) {
                            mutableSetOf()
                        }.add(node.swiftPMDependency)
                    }
                }
                is SwiftPMDependenciesMetadataNode -> {
                    val parentMavenNode = node.parents.firstNotNullOf { it as? MavenDependencyNode }.dependency.coordinates
                    val identifier = with(parentMavenNode) {
                        "${groupId}_${artifactId}_${version}".replace(Regex("[^a-zA-Z0-9]"), "_")
                    }
                    val swiftPMMetadata = node.swiftPMMetadataPath.inputStream().use {
                        @OptIn(ExperimentalSerializationApi::class)
                        json.decodeFromStream<SwiftPMImportMetadata>(it)
                    }
                    transitiveMavenSwiftPMDependencies[SwiftPMDependencyIdentifier(identifier)] = swiftPMMetadata
                }
            }
        }

        val metadataByDependencyIdentifier: Map<SwiftPMDependencyIdentifier, SwiftPMImportMetadata> = transitiveAmperModuleSwiftPMDependencies.map {
            SwiftPMDependencyIdentifier(it.key) to swiftPMMetadataByAmperModuleName[it.key]!!.copy(
                dependencies = it.value
            )
        }.toMap() + transitiveMavenSwiftPMDependencies

        return SwiftPMDependencies(
            directSwiftPMDependencies = directSwiftPMDependencies,
            transitiveSwiftPMDependencies = TransitiveSwiftPMMetadata(
                metadataByDependencyIdentifier = metadataByDependencyIdentifier,
            )
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}