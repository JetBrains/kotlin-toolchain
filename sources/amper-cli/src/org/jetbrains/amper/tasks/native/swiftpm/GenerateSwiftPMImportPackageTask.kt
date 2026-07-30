/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.swiftpm.SwiftPMDependencies
import org.jetbrains.amper.swiftpm.SwiftPMDependency
import org.jetbrains.amper.swiftpm.SwiftPMDependencyIdentifier
import org.jetbrains.amper.swiftpm.TransitiveSwiftPMMetadata
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactType
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.ios.xcodeProjectPath
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.IOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.MACOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.TVOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportDefaults.WATCHOS_DEPLOYMENT_TARGET_DEFAULT
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

sealed interface GenerateSwiftPMImportPackageArtifact : Artifact {
    val module: AmperModule
}
class XcodeWiredSwiftPMImportPackage(override val path: Path, override val module: AmperModule) : GenerateSwiftPMImportPackageArtifact
class InternalSwiftPMImportPackage(override val path: Path, override val module: AmperModule) : GenerateSwiftPMImportPackageArtifact

inline fun <reified T: GenerateSwiftPMImportPackageArtifact> generatedPackage(module: AmperModule) = ArtifactSelector(
    type = ArtifactType(T::class),
    predicate = { it.module == module },
    description = "SwiftPMImportPackage",
    quantifier = Quantifier.Single,
)

/**
 * @see GenerateSwiftPMImportPackageTask
 */
class XcodeWiredGenerateSwiftPMImportPackageTask(
    module: AmperModule,
    taskName: TaskName,
    incrementalCache: IncrementalCache,
) : GenerateSwiftPMImportPackageTask<XcodeWiredSwiftPMImportPackage>(
    module = module,
    taskName = taskName,
    syntheticProductType = SyntheticProductType.INFERRED,
    incrementalCache = incrementalCache,
    incrementalCacheKey = "xcode-wired-package-generation",
    artifact = XcodeWiredSwiftPMImportPackage(
        path = module.xcodeProjectPath.parent.resolve(SYNTHETIC_IMPORT_TARGET_MAGIC_NAME),
        module = module
    )
)

/**
 * @see GenerateSwiftPMImportPackageTask
 */
class InternalGenerateSwiftPMImportPackageTask(
    module: AmperModule,
    taskName: TaskName,
    taskOutputRoot: TaskOutputRoot,
    incrementalCache: IncrementalCache,
) : GenerateSwiftPMImportPackageTask<InternalSwiftPMImportPackage>(
    module = module,
    taskName = taskName,
    syntheticProductType = SyntheticProductType.DYNAMIC,
    incrementalCache = incrementalCache,
    incrementalCacheKey = "internal-package-generation",
    artifact = InternalSwiftPMImportPackage(
        path = taskOutputRoot.path.resolve("swiftImport"),
        module = module,
    )
)

/**
 * We generate 2 packages in SwiftPM import:
 * - [InternalGenerateSwiftPMImportPackageTask] is generated in the build directory and used to generate def files and do
 * the ld dump for K/N tests and executables linkage.
 * - [XcodeWiredGenerateSwiftPMImportPackageTask] is used for Xcode-side linkage and is generated adjacent to the Xcode
 * project (with intention that it will be commited to git) which is necessary for the Xcode project to be opennable on
 * a clean checkout.
 *
 * [XcodeWiredGenerateSwiftPMImportPackageTask] always has [SyntheticProductType.INFERRED] because Kotlin Toolchain has only
 * static framework linkage (in Gradle we also model the dynamic framework using [SyntheticProductType.DYNAMIC]), and the
 * internal package has [SyntheticProductType.DYNAMIC] to force ld shim to be called.
 */
abstract class GenerateSwiftPMImportPackageTask <T: GenerateSwiftPMImportPackageArtifact> internal constructor(
    val module: AmperModule,
    override val taskName: TaskName,
    private val syntheticProductType: SyntheticProductType,
    private val incrementalCache: IncrementalCache,
    val incrementalCacheKey: String,
    val artifact: T
) : ArtifactTaskBase() {

    private val outgoingPackage by artifact
    private val syntheticImportProjectRoot = outgoingPackage.path

    enum class SyntheticProductType {
        DYNAMIC,
        INFERRED,
    }

    enum class Family {
        OSX,
        TVOS,
        IOS,
        WATCHOS
    }

    private val swiftPMDependenciesArtifact by swiftPMDependenciesArtifact(module)
    private val appleFragments = module.leafAppleFragments()

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val swiftPMDependencies = swiftPMDependenciesArtifact.swiftPMDependencies
        if (!swiftPMDependencies.hasDirectOrTransitiveSwiftPMDependencies) {
            produces.single().path.deleteRecursively()
            return EmptyTaskResult
        }
        // FIXME: Do the idempotency check and only do it when running from IDEs
        incrementalCache.executeForFiles(
            key = "${incrementalCacheKey}-${module.userReadableName}",
            inputValues = mapOf(
                "packageGraph" to swiftPMJson.encodeToString(swiftPMDependencies),
            ),
            inputFiles = emptyList(),
        ) {
            spanBuilder(incrementalCacheKey).setAmperModule(module).use {
                runPackageGeneration(
                    swiftPMDependencies = swiftPMDependencies,
                    syntheticImportProjectRoot = syntheticImportProjectRoot,
                )
                val subpackages = syntheticImportProjectRoot.resolve(SUBPACKAGES)
                val subpackagePaths = if (subpackages.exists()) {
                    subpackages.listDirectoryEntries().filter { !it.name.startsWith(".") }.flatMap {
                        listOf(
                            it.resolve("Package.swift"),
                            it.resolve("Sources")
                        )
                    }
                } else emptyList()
                listOf(
                    syntheticImportProjectRoot.resolve("Package.swift"),
                    syntheticImportProjectRoot.resolve("Sources"),
                ) + subpackagePaths
            }
        }
        return EmptyTaskResult
    }

    private fun Path.normalizedAbsoluteFile(): Path =
        toAbsolutePath().normalize()

    context(_: ProblemReporter)
    private suspend fun runPackageGeneration(
        swiftPMDependencies: SwiftPMDependencies,
        syntheticImportProjectRoot: Path,
    ): TaskResult {
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
            SyntheticProductType.INFERRED -> {
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

    companion object {
        const val SYNTHETIC_IMPORT_TARGET_MAGIC_NAME = "KotlinMultiplatformLinkedPackage"
        const val SYNTHETIC_IMPORT_DYLIB = "KotlinMultiplatformLinkedPackageDylib"
        const val SUBPACKAGES = "subpackages"
        const val MANIFEST_NAME = "Package.swift"
    }
}
