/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.schema.swiftpm.SwiftPMDependency
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactTask
import org.jetbrains.amper.tasks.ios.xcodeprojPath
import org.jetbrains.amper.tasks.native.leafAppleFragments
import org.jetbrains.amper.tasks.native.normalizedAbsoluteFile
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.IOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.MACOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.MANIFEST_NAME
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.SUBPACKAGES
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.SYNTHETIC_IMPORT_DYLIB
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.SYNTHETIC_IMPORT_TARGET_MAGIC_NAME
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.TVOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.WATCHOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Family
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.SyntheticProductType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

class XcodeWiredSwiftPMImportPackage(override val path: Path, val module: AmperModule) : Artifact
class InternalSwiftPMImportPackage(override val path: Path, val module: AmperModule) : Artifact

class XcodeWiredGenerateSwiftPMImportPackage(
    module: AmperModule,
    taskName: TaskName,
    transitiveSwiftPMDependenciesResolver: TransitiveSwiftPMDependenciesResolver,
) : GenerateSwiftPMImportPackage(
    module = module,
    taskName = taskName,
    transitiveSwiftPMDependenciesResolver = transitiveSwiftPMDependenciesResolver,
    syntheticImportProjectRoot = module.xcodeprojPath().parent.resolve(SYNTHETIC_IMPORT_TARGET_MAGIC_NAME),
    syntheticProductType = SyntheticProductType.INFERRED,
) {
    override val produces: List<Artifact> = listOf(
        XcodeWiredSwiftPMImportPackage(syntheticImportProjectRoot, module)
    )
}

class InternalGenerateSwiftPMImportPackage(
    module: AmperModule,
    taskName: TaskName,
    taskOutputRoot: TaskOutputRoot,
    transitiveSwiftPMDependenciesResolver: TransitiveSwiftPMDependenciesResolver,
) : GenerateSwiftPMImportPackage(
    module = module,
    taskName = taskName,
    transitiveSwiftPMDependenciesResolver = transitiveSwiftPMDependenciesResolver,
    syntheticImportProjectRoot = taskOutputRoot.path.resolve("swiftImport"),
    syntheticProductType = SyntheticProductType.DYNAMIC,
) {
    override val produces: List<Artifact> = listOf(
        InternalSwiftPMImportPackage(syntheticImportProjectRoot, module)
    )
}

abstract class GenerateSwiftPMImportPackage internal constructor(
    val module: AmperModule,
    override val taskName: TaskName,
    private val transitiveSwiftPMDependenciesResolver: TransitiveSwiftPMDependenciesResolver,
    protected val syntheticImportProjectRoot: Path,
    private val syntheticProductType: SyntheticProductType,
) : ArtifactTask {

    private val appleFragments = module.leafAppleFragments()

    override val consumes: List<ArtifactSelector<*, *>> = emptyList()
    abstract override val produces: List<Artifact>

    override fun injectConsumes(artifacts: Map<ArtifactSelector<*, *>, List<Artifact>>) {}

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        // FIXME: Do the idempotency check and only do it when running from IDEs
        return runPackageGeneration(syntheticImportProjectRoot)
    }

    private suspend fun runPackageGeneration(
        syntheticImportProjectRoot: Path,
    ): TaskResult {
        val swiftPMDependencies = transitiveSwiftPMDependenciesResolver.resolve()
        if (!swiftPMDependencies.hasDirectOrTransitiveSwiftPMDependencies) {
            produces.single().path.deleteRecursively()
            return EmptyTaskResult
        }

        val packageRoot = syntheticImportProjectRoot.normalizedAbsoluteFile()

        val directSwiftPMDependencies = swiftPMDependencies.directSwiftPMDependencies
        val packageIdentifier = SYNTHETIC_IMPORT_TARGET_MAGIC_NAME
        when (syntheticProductType) {
            SyntheticProductType.DYNAMIC -> {
                generatePackageManifest(
                    identifier = SYNTHETIC_IMPORT_DYLIB,
                    packageRoot = packageRoot.resolve("${SUBPACKAGES}/${SYNTHETIC_IMPORT_DYLIB}"),
                    syntheticProductType = SyntheticProductType.DYNAMIC,
                    directlyImportedSwiftPMDependencies = directSwiftPMDependencies,
                    transitiveSyntheticPackages = swiftPMDependencies.transitiveSwiftPMDependencies.metadataByDependencyIdentifier.keys,
                    transitiveSyntheticPackagesPath = "..",
                    transitiveSwiftPMMetadata = swiftPMDependencies.transitiveSwiftPMDependencies,
                )
                generatePackageManifest(
                    identifier = packageIdentifier,
                    packageRoot = packageRoot,
                    syntheticProductType = SyntheticProductType.INFERRED,
                    // Leave only version constraints - SwiftPM doesn't pick it up from subproject dependency when product is not consumed explicitly from the package
                    directlyImportedSwiftPMDependencies = directSwiftPMDependencies.mapNotNull {
                        val remoteDependency = when (it) {
                            is SwiftPMDependency.Local -> return@mapNotNull null
                            is SwiftPMDependency.Remote -> it
                        }
                        remoteDependency.copy(products = emptyList())
                    }.toSet(),
                    transitiveSyntheticPackages = setOf(SwiftPMDependencyIdentifier(SYNTHETIC_IMPORT_DYLIB)),
                    transitiveSyntheticPackagesPath = SUBPACKAGES,
                    transitiveSwiftPMMetadata = swiftPMDependencies.transitiveSwiftPMDependencies,
                )
            }
            SyntheticProductType.INFERRED, null -> {
                generatePackageManifest(
                    identifier = packageIdentifier,
                    packageRoot = packageRoot,
                    syntheticProductType = SyntheticProductType.INFERRED,
                    directlyImportedSwiftPMDependencies = directSwiftPMDependencies,
                    transitiveSyntheticPackages = swiftPMDependencies.transitiveSwiftPMDependencies.metadataByDependencyIdentifier.keys,
                    transitiveSyntheticPackagesPath = SUBPACKAGES,
                    transitiveSwiftPMMetadata = swiftPMDependencies.transitiveSwiftPMDependencies,
                )
            }
        }

        swiftPMDependencies.transitiveSwiftPMDependencies.metadataByDependencyIdentifier.forEach { [dependencyIdentifier, transitiveMetadata] ->
            // FIXME: Support implicit constraints
            generatePackageManifest(
                identifier = dependencyIdentifier.identifier,
                packageRoot = packageRoot.resolve("${SUBPACKAGES}/${dependencyIdentifier.identifier}"),
                /**
                 * FIXME: KT-83873 We probably always want inferred here, but figure out what is wrong with SwiftPM's linkage when 2 .dynamic products are involved
                 *
                 * Also all the project/modular dependencies will litter embedAndSign integration with useless dylibs
                 */
                syntheticProductType = SyntheticProductType.INFERRED,
                directlyImportedSwiftPMDependencies = transitiveMetadata.dependencies,
                transitiveSyntheticPackages = setOf(),
                transitiveSyntheticPackagesPath = "..",
                transitiveSwiftPMMetadata = swiftPMDependencies.transitiveSwiftPMDependencies,
            )
        }

        return EmptyTaskResult
    }

    private fun generatePackageManifest(
        identifier: String,
        packageRoot: Path,
        syntheticProductType: SyntheticProductType,
        directlyImportedSwiftPMDependencies: Set<SwiftPMDependency>,
        transitiveSyntheticPackages: Set<SwiftPMDependencyIdentifier>,
        transitiveSyntheticPackagesPath: String,
        transitiveSwiftPMMetadata: TransitiveSwiftPMMetadata
    ) {
        val repoDependencies = (directlyImportedSwiftPMDependencies.map { importedPackage ->
            buildString {
                appendLine(".package(")
                val dependencyArguments = mutableListOf<String>()
                when (importedPackage) {
                    is SwiftPMDependency.Remote -> {
                        dependencyArguments += when (val repository = importedPackage.repository) {
                            is SwiftPMDependency.Remote.Repository.Id -> "  id: \"${repository.value}\""
                            is SwiftPMDependency.Remote.Repository.Url -> "  url: \"${repository.value}\""
                        }
                        dependencyArguments += when (val version = importedPackage.version) {
                            is SwiftPMDependency.Remote.Version.Exact -> "  exact: \"${version.value}\""
                            is SwiftPMDependency.Remote.Version.From -> "  from: \"${version.value}\""
                            is SwiftPMDependency.Remote.Version.Range -> "  \"${version.from}\"...\"${version.through}\""
                            is SwiftPMDependency.Remote.Version.Branch -> "  branch: \"${version.value}\""
                            is SwiftPMDependency.Remote.Version.Revision -> "  revision: \"${version.value}\""
                        }
                    }
                    is SwiftPMDependency.Local -> {
                        val absolutePath = importedPackage.absolutePath
                        val relativePath = absolutePath.normalizedAbsoluteFile().relativeTo(packageRoot)
                        dependencyArguments += "  path: \"${relativePath.pathString}\""
                    }
                }
                if (importedPackage.traits.isNotEmpty()) {
                    val traitsString = importedPackage.traits.joinToString(", ") { "\"${it}\"" }
                    dependencyArguments += "  traits: [${traitsString}]"
                }
                appendLine(dependencyArguments.joinToString(",\n"))
                append(")")
            }
        } + transitiveSyntheticPackages.map {
            ".package(path: \"${transitiveSyntheticPackagesPath}/${it.identifier}\")"
        })
        val targetDependencies = (directlyImportedSwiftPMDependencies.flatMap { dependency ->
            dependency.products.map { product -> product to dependency.packageName }
        }.map { dependency ->
            buildString {
                appendLine(".product(")
                val dependencyArguments = mutableListOf<String>()
                dependencyArguments += "  name: \"${dependency.first.name}\""
                dependencyArguments += "  package: \"${dependency.second}\""
                val platformConstraints = dependency.first.platformConstraints
                if (platformConstraints != null) {
                    val platformsString = platformConstraints.joinToString(", ") { platform -> ".${platform.swiftEnumName}" }
                    dependencyArguments += "  condition: .when(platforms: [${platformsString}])"
                }
                appendLine(dependencyArguments.joinToString(",\n"))
                append(")")
            }
        } + transitiveSyntheticPackages.map {
            ".product(name: \"${it.identifier}\", package: \"${it.identifier}\")"
        })

        val platforms = appleFragments.map {
            when {
                it.platform.isDescendantOf(Platform.IOS) -> Family.IOS
                it.platform.isDescendantOf(Platform.TVOS) -> Family.TVOS
                it.platform.isDescendantOf(Platform.WATCHOS) -> Family.WATCHOS
                it.platform.isDescendantOf(Platform.MACOS) -> Family.OSX
                else -> error("...")
            }
        }.toSet().map {
            when (it) {
                Family.OSX -> {
                    val deploymentTarget = maximumDeploymentTarget(
                        MACOS_DEPLOYMENT_TARGET_DEFAULT,

                        transitiveSwiftPMMetadata.metadataByDependencyIdentifier.values.mapNotNull { it.macosDeploymentVersion },
                    )
                    ".macOS(\"${deploymentTarget}\")"
                }
                Family.IOS -> {
                    val deploymentTarget = maximumDeploymentTarget(
                        IOS_DEPLOYMENT_TARGET_DEFAULT,
                        transitiveSwiftPMMetadata.metadataByDependencyIdentifier.values.mapNotNull { it.iosDeploymentVersion },
                    )
                    ".iOS(\"${deploymentTarget}\")"
                }
                Family.TVOS -> {
                    val deploymentTarget = maximumDeploymentTarget(
                        TVOS_DEPLOYMENT_TARGET_DEFAULT,
                        transitiveSwiftPMMetadata.metadataByDependencyIdentifier.values.mapNotNull { it.tvosDeploymentVersion },
                    )
                    ".tvOS(\"${deploymentTarget}\")"
                }
                Family.WATCHOS -> {
                    val deploymentTarget = maximumDeploymentTarget(
                        WATCHOS_DEPLOYMENT_TARGET_DEFAULT,
                        transitiveSwiftPMMetadata.metadataByDependencyIdentifier.values.mapNotNull { it.watchosDeploymentVersion },
                    )
                    ".watchOS(\"${deploymentTarget}\")"
                }
            }
        }

        val productType = when (syntheticProductType) {
            SyntheticProductType.DYNAMIC -> ".dynamic"
            SyntheticProductType.INFERRED -> ".none"
        }

        val manifest = packageRoot.resolve(MANIFEST_NAME)
        manifest.also {
            it.parent.createDirectories()
        }.writeText(
            SwiftPMImportManifestGenerator.generateManifest(
                identifier = identifier,
                productType = productType,
                platforms = platforms,
                repoDependencies = repoDependencies,
                targetDependencies = targetDependencies
            )
        )

        val objcSource = "Sources/${identifier}/${identifier}.m"
        val objcHeader = "Sources/${identifier}/include/${identifier}.h"
        // Generate ObjC sources specifically because the next CC-overriding step relies on passing a clang shim to dump compiler arguments
        packageRoot.resolve(objcSource).also {
            it.parent.createDirectories()
        }.writeText("")
        packageRoot.resolve(objcHeader).also {
            it.parent.createDirectories()
        }.writeText("")

        val moduleMap = "Sources/${identifier}/include/module.modulemap"
        packageRoot.resolve(moduleMap).also {
            it.parent.createDirectories()
        }.writeText(
            ""
        )

    }

    private fun maximumDeploymentTarget(
        deploymentVersionDefault: String,
        transitivelyImportedDeploymentVersions: List<String>,
    ): String {
        val maximumDeploymentTarget = transitivelyImportedDeploymentVersions.fold(
            DeploymentVersion.parse(deploymentVersionDefault),
        ) { max, current ->
            val other = DeploymentVersion.parse(current)
            if (max >= other) {
                max
            } else {
                other
            }
        }
        return "${maximumDeploymentTarget.major}.${maximumDeploymentTarget.minor}"
    }

    data class DeploymentVersion(
        val major: Int,
        val minor: Int
    ): Comparable<DeploymentVersion> {
        override fun compareTo(other: DeploymentVersion): Int {
            return compareValuesBy(this, other, DeploymentVersion::major, DeploymentVersion::minor)
        }

        companion object {
            fun parse(text: String): DeploymentVersion {
                val majorIndex = text.indexOf(".")
                return DeploymentVersion(
                    major = text.substring(0, majorIndex).toInt(),
                    minor = text.substring(majorIndex + 1, text.length).toInt()
                )
            }
        }
    }
}