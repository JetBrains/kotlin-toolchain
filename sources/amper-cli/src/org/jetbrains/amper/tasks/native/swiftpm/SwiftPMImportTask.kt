/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.process
import org.jetbrains.amper.processes.run
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.CinteropDefFileArtifact
import org.jetbrains.amper.tasks.artifacts.KotlinNativeLinkerArgumentsArtifact
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactTask
import org.jetbrains.amper.tasks.artifacts.api.ArtifactType
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.native.swiftpm.XcodebuildDefFileUtils
import org.jetbrains.amper.tasks.native.clangArch
import org.jetbrains.amper.tasks.native.hasDirectSwiftPMDependencies
import org.jetbrains.amper.tasks.native.leafAppleFragments
import org.jetbrains.amper.tasks.native.unwrap
import org.jetbrains.amper.tasks.native.xcodebuildPlatform
import org.jetbrains.amper.tasks.native.xcodebuildSdk
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.io.path.writeText

// FIXME: Split this task into separate fetch and xcodebuild step and register xcodebuild step per leafFragment group as in Gradle
internal class SwiftPMImportTask(
    val module: AmperModule,
    val platform: Platform,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    val isTest: Boolean,
    val buildType: BuildType,
    private val buildOutputRoot: AmperBuildOutputRoot,
): ArtifactTask {
    private fun defFileOutputPath(leafFragment: LeafFragment) = taskOutputRoot.path.resolve("${leafFragment.name}/${module.userReadableName}_swiftPMImport.def")
    private fun linkerArgumentsOutputPath(leafFragment: LeafFragment) = taskOutputRoot.path.resolve("${leafFragment.name}/linkerDump")

    private val appleFragments = module.leafAppleFragments()

    private val generatedPackage = ArtifactSelector<InternalSwiftPMImportPackage, Quantifier.Single>(
        type = ArtifactType(InternalSwiftPMImportPackage::class),
        predicate = { it.module == module },
        description = "SwiftPMImportPackage",
        quantifier = Quantifier.Single,
    )
    override val consumes: List<ArtifactSelector<*, *>> = listOf(
        generatedPackage,
    )

    // We only want to generate def files and make cinterops run if we have direct SwiftPM dependencies
    private val cinteropArtifacts = if (module.hasDirectSwiftPMDependencies()) appleFragments.map { appleFragment ->
        CinteropDefFileArtifact(
            buildOutputRoot = buildOutputRoot,
            fragment = appleFragment,
            conventionPath = defFileOutputPath(appleFragment),
        )
    } else emptyList<Artifact>()

    private val linkageArtifacts = appleFragments.map { appleFragment ->
        // FIXME: Actually output and consume these in non-static framework NativeLinkTask
        KotlinNativeLinkerArgumentsArtifact(
            buildOutputRoot = buildOutputRoot,
            fragment = appleFragment,
            conventionPath = linkerArgumentsOutputPath(appleFragment),
        )
    }

    override val produces: List<Artifact> = cinteropArtifacts + linkageArtifacts

    enum class SyntheticProductType : Serializable {
        DYNAMIC,
        INFERRED,
    }

    enum class Family : Serializable {
        OSX,
        TVOS,
        IOS,
        WATCHOS
    }

    private suspend fun fetchPackage(
        syntheticImportProjectRoot: Path,
        swiftPMDependenciesCheckout: Path,
    ) {
        val resolve = mutableListOf(
            "/usr/bin/swift",
            "package",
            "--scratch-path", swiftPMDependenciesCheckout.pathString,
            "resolve",
        )
        val pb = process(
            workingDir = syntheticImportProjectRoot,
            command = resolve,
        )
        val environmentToFilter = listOf("SDKROOT")
        environmentToFilter.forEach { key ->
            if (pb.environment().containsKey(key)) {
                pb.environment().remove(key)
            }
        }
        pb.run(
            outputListener = LoggingProcessOutputListener(logger, "SwiftPM import fetch: "),
        )
    }

    private suspend fun generateDefFilesAndLinkerDump(
        syntheticImportProjectRoot: Path,
        swiftPMDependenciesCheckout: Path,
        xcodebuildCall: XcodebuildCall,
        targetFragments: List<LeafFragment>,
    ) {
        val xcodebuildPlatform = xcodebuildCall.platform
        val xcodebuildSdk = xcodebuildCall.sdk

        val dumpedXcodeBuildArgsDir = taskOutputRoot.path.resolve("swiftImportDump/${xcodebuildSdk}").also { it.parent.createDirectories() }
        dumpedXcodeBuildArgsDir.also {
            if (it.exists()) {
                it.deleteRecursively()
            }
            it.createDirectories()
        }

        val clangArgsDumpScript = dumpedXcodeBuildArgsDir.resolve("clangDump.sh")
        clangArgsDumpScript.writeText(XcodebuildDefFileUtils.clangArgsDumpScript())
        clangArgsDumpScript.toFile().setExecutable(true)
        val clangArgsDump = dumpedXcodeBuildArgsDir.resolve("clang_args_dump")
        clangArgsDump.createDirectories()

        val ldArgsDumpScript = dumpedXcodeBuildArgsDir.resolve("ldDump.sh")
        ldArgsDumpScript.writeText(XcodebuildDefFileUtils.ldArgsDumpScript())
        ldArgsDumpScript.toFile().setExecutable(true)
        val ldArgsDump = dumpedXcodeBuildArgsDir.resolve("ld_args_dump")
        ldArgsDump.createDirectories()

        val dd = taskOutputRoot.path.resolve("swiftImportDd/dd_${xcodebuildSdk}").also { it.createDirectories() }
        val targetArchitectures: List<String> = targetFragments.map {
            it.platform.clangArch
        }
        val args = mutableListOf(
            "xcodebuild", "build",
            "-scheme", SYNTHETIC_IMPORT_TARGET_MAGIC_NAME,
            "-destination", "generic/platform=${xcodebuildPlatform}",
            "-derivedDataPath", dd.pathString,
            "-clonedSourcePackagesDirPath", swiftPMDependenciesCheckout.pathString,
            "CC=${clangArgsDumpScript.pathString}",
            "ALTERNATE_LINKER=${ldArgsDumpScript.pathString}",
            "ARCHS=${targetArchitectures.joinToString(" ")}",
            "CODE_SIGN_IDENTITY=",
            "COMPILER_INDEX_STORE_ENABLE=NO",
            "SWIFT_INDEX_STORE_ENABLE=NO",
        )

        val pb = process(
            workingDir = syntheticImportProjectRoot,
            command = args,
            environment = mapOf(
                XcodebuildDefFileUtils.KOTLIN_CLANG_ARGS_DUMP_FILE_ENV to clangArgsDump.pathString,
                XcodebuildDefFileUtils.KOTLIN_LD_ARGS_DUMP_FILE_ENV to ldArgsDump.pathString,
            ),
        )
        val environmentToFilter = listOf(
            "EMBED_PACKAGE_RESOURCE_BUNDLE_NAMES",
            "ENABLE_DEBUG_DYLIB",
            "EXECUTABLE_BLANK_INJECTION_DYLIB_PATH",
            "EXECUTABLE_DEBUG_DYLIB_INSTALL_NAME",
            "EXECUTABLE_DEBUG_DYLIB_PATH"
        )
        environmentToFilter.forEach {
            if (pb.environment().containsKey(it)) {
                pb.environment().remove(it)
            }
        }
        pb.environment().keys.filter {
            it.startsWith("OTHER_") || it.startsWith("ASSETCATALOG_")
        }.forEach {
            pb.environment().remove(it)
        }

        val xcodebuildResult = pb.run(outputListener = LoggingProcessOutputListener(logger, "xcodebuild: "))
        if (xcodebuildResult != 0) {
            userReadableError("xcodebuild failed with exit code $xcodebuildResult")
        }

        targetFragments.forEach { fragment ->
            val clangArchitecture = fragment.platform.clangArch
            val architectureSpecificProductClangCalls = mutableListOf<Path>()

            clangArgsDump.listFilesOrEmpty().filter {
                it.isRegularFile()
            }.forEach {
                val clangArgs = it.readLines().single()
                val isArchitectureSpecificProductClangCall =
                    "-fmodule-name=${SYNTHETIC_IMPORT_DYLIB}" in clangArgs
                            && "-target${XcodebuildDefFileUtils.DUMP_FILE_ARGS_SEPARATOR}${clangArchitecture}-apple" in clangArgs
                if (isArchitectureSpecificProductClangCall) {
                    architectureSpecificProductClangCalls.add(it)
                }
            }

            if (architectureSpecificProductClangCalls.isEmpty()) {
                userReadableError("No clang calls discovered for ${fragment.name}")
            }
            if (architectureSpecificProductClangCalls.size > 1) {
                userReadableError("Multiple clang calls discovered for ${fragment.name}: ${architectureSpecificProductClangCalls.joinToString(", ")}")
            }
            val parsedClangCall = XcodebuildDefFileUtils.parseClangCall(architectureSpecificProductClangCalls.single())

            val clangModules = XcodebuildDefFileUtils.discoverClangModules(parsedClangCall)

            XcodebuildDefFileUtils.writeDefFile(
                parsedClangCall = parsedClangCall,
                clangModules = clangModules,
                defFile = defFileOutputPath(fragment).also { it.parent.createDirectories() },
                cinteropNamespace = "swiftPMImport.${module.userReadableName}",
                discoverModulesImplicitly = true,
            )
        }
    }

    private var artifacts: Map<ArtifactSelector<*, *>, List<Artifact>> = emptyMap()
    override fun injectConsumes(artifacts: Map<ArtifactSelector<*, *>, List<Artifact>>) {
        this.artifacts = artifacts
    }

    data class XcodebuildCall(
        val platform: String,
        val sdk: String,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        if (!artifacts.unwrap(generatedPackage).path.exists()) {
            produces.forEach {
                it.path.deleteRecursively()
            }
            return EmptyTaskResult
        }

        val syntheticImportProjectRoot = artifacts.unwrap(generatedPackage).path
        val swiftPMDependenciesCheckout = taskOutputRoot.path.resolve("swiftPMCheckout")
        fetchPackage(
            syntheticImportProjectRoot = syntheticImportProjectRoot,
            swiftPMDependenciesCheckout = swiftPMDependenciesCheckout,
        )

        coroutineScope {
            module.leafAppleFragments().groupBy {
                XcodebuildCall(
                    it.platform.xcodebuildPlatform, it.platform.xcodebuildSdk
                )
            }.map { [call, fragments] ->
                async {
                    generateDefFilesAndLinkerDump(
                        syntheticImportProjectRoot = syntheticImportProjectRoot,
                        swiftPMDependenciesCheckout = swiftPMDependenciesCheckout,
                        xcodebuildCall = call,
                        targetFragments = fragments,
                    )
                }
            }
        }.awaitAll()

        return EmptyTaskResult
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val SYNTHETIC_IMPORT_TARGET_MAGIC_NAME = "KotlinMultiplatformLinkedPackage"
        const val SYNTHETIC_IMPORT_DYLIB = "KotlinMultiplatformLinkedPackageDylib"
        const val SUBPACKAGES = "subpackages"
        const val MANIFEST_NAME = "Package.swift"
        const val IOS_DEPLOYMENT_TARGET_DEFAULT = "15.0"
        const val MACOS_DEPLOYMENT_TARGET_DEFAULT = "12.0"
        const val WATCHOS_DEPLOYMENT_TARGET_DEFAULT = "9.0"
        const val TVOS_DEPLOYMENT_TARGET_DEFAULT = "15.0"
    }
}
