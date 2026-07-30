/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.PrintToTerminalProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.pipe.ProcessPipe
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.CinteropDefFileArtifact
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactType
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.tasks.ios.IosBuildTask
import org.jetbrains.amper.tasks.native.swiftpm.GenerateSwiftPMImportPackageTask.Companion.SYNTHETIC_IMPORT_DYLIB
import org.jetbrains.amper.tasks.native.swiftpm.GenerateSwiftPMImportPackageTask.Companion.SYNTHETIC_IMPORT_TARGET_MAGIC_NAME
import org.jetbrains.amper.tasks.native.swiftpm.XcodebuildDefFileUtils.DUMP_FILE_ARGS_SEPARATOR
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.io.use

class SwiftPMImportParsedLdCallArtifact(
    override val path: Path,
    val platform: Platform,
    val module: AmperModule
) : Artifact {
    internal val parsedLdCall: XcodebuildDefFileUtils.ParsedLdCall? by lazy {
        if (!path.exists()) return@lazy null
        path.inputStream().use {
            @OptIn(ExperimentalSerializationApi::class)
            swiftPMJson.decodeFromStream<XcodebuildDefFileUtils.ParsedLdCall>(it)
        }
    }
}

fun parsedLdCallArtifact(module: AmperModule, platform: Platform) = ArtifactSelector(
    type = ArtifactType(SwiftPMImportParsedLdCallArtifact::class),
    predicate = { it.module == module && it.platform == platform },
    description = "SwiftPMLdDump",
    quantifier = Quantifier.SingleOrNone,
)

internal class SwiftPMImportTask(
    val module: AmperModule,
    val platform: XcodebuildPlatform,
    val targetFragments: Set<LeafFragment>,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    private val buildOutputRoot: AmperBuildOutputRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val terminal: Terminal,
): ArtifactTaskBase() {
    // FIXME: Also output def file per platform?
    private fun defFileOutputPath(leafFragment: LeafFragment) = taskOutputRoot.path.resolve("${leafFragment.name}/${module.userReadableName}_swiftPMImport.def")
    private fun linkerArgumentsOutputPath(leafPlatform: Platform) = taskOutputRoot.path.resolve("${leafPlatform.name}/linkerDump")

    private val fetchedPackage by internalFetchedPackage(module)
    private val generatedPackage by generatedPackage<InternalSwiftPMImportPackage>(module)
    private val localPackageInputsArtifact by localPackageInputs(module)

    // We only want to generate def files and make cinterops run if we have direct SwiftPM dependencies
    private val cinteropArtifacts by if (module.hasDirectSwiftPMDependencies()) {
        val cinteropDefArtifacts = targetFragments.map { appleFragment ->
            CinteropDefFileArtifact(
                buildOutputRoot = buildOutputRoot,
                fragment = appleFragment,
                conventionPath = defFileOutputPath(appleFragment),
            )
        }
        cinteropDefArtifacts
    } else emptyList()

    private val linkageArtifacts by targetFragments.map { it.platform }.toSet().map { platform ->
        SwiftPMImportParsedLdCallArtifact(
            path = linkerArgumentsOutputPath(platform),
            module = module,
            platform = platform,
        )
    }

    private suspend fun generateDefFilesAndLinkerDump(
        syntheticImportProjectRoot: Path,
        swiftPMDependenciesCheckout: Path,
    ) {
        val xcodebuildSdk = platform.sdk
        val xcodebuildDestination = platform.destination

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
        // FIXME: KT-84809 - This is not great, but we can't remove entire DD on incremental runs.
        // We delete only the synthetic dylib target intermediates to force xcodebuild to call the wrapper scripts again.
        // Other DerivedData contents are kept because SwiftPM/Xcode may need products and module maps from dependency
        // targets, and deleting the whole directory is too expensive for incremental dump runs.
        val forceClangToReexecute =
            dd.resolve("Build/Intermediates.noindex/${SYNTHETIC_IMPORT_DYLIB}.build")
        if (forceClangToReexecute.exists()) {
            forceClangToReexecute.deleteRecursively()
        }

        val targetArchitectures = targetFragments.map {
            it.platform.clangArch
        }.toSet()

        val args = mutableListOf(
            "xcodebuild", "build",
            "-scheme", SYNTHETIC_IMPORT_TARGET_MAGIC_NAME,
            "-destination", "generic/platform=${xcodebuildDestination}",
            "-derivedDataPath", dd.pathString,
            "-clonedSourcePackagesDirPath", swiftPMDependenciesCheckout.pathString,
            "CC=${clangArgsDumpScript.pathString}",
            "ALTERNATE_LINKER=${ldArgsDumpScript.pathString}",
            "ARCHS=${targetArchitectures.joinToString(" ")}",
            "CODE_SIGN_IDENTITY=",
            "COMPILER_INDEX_STORE_ENABLE=NO",
            "SWIFT_INDEX_STORE_ENABLE=NO",
        )

        val xcbeautyCli = IosBuildTask.prepareLogParsingUtility(userCacheRoot)
        val pipe = ProcessPipe(
            includeStderr = true,
            eavesDroppingListener = LoggingProcessOutputListener(
                logger = logger,
                prefix = "SwiftPM import xcodebuild/out",
                stdErrPrefix = "SwiftPM import xcodebuild/err",
                stdoutLoggingLevel = Level.DEBUG,
                stderrLoggingLevel = Level.DEBUG,
            ),
        )

        coroutineScope {
            val parserProcessJob = launch {
                runProcess(
                    command = [
                        xcbeautyCli.pathString,
                        "--disable-logging",
                        "--quiet",
                    ],
                    outputMode = ProcessOutputMode.listen(PrintToTerminalProcessOutputListener(terminal)),
                    input = pipe,
                )
            }

            val xcodebuildResult = runProcess(
                workingDir = syntheticImportProjectRoot,
                command = args,
                configureEnvironment = { env ->
                    env.putAll(
                        mapOf(
                            XcodebuildDefFileUtils.KOTLIN_CLANG_ARGS_DUMP_FILE_ENV to clangArgsDump.pathString,
                            XcodebuildDefFileUtils.KOTLIN_LD_ARGS_DUMP_FILE_ENV to ldArgsDump.pathString,
                        )
                    )
                    val environmentToFilter = listOf(
                        "EMBED_PACKAGE_RESOURCE_BUNDLE_NAMES",
                        "ENABLE_DEBUG_DYLIB",
                        "EXECUTABLE_BLANK_INJECTION_DYLIB_PATH",
                        "EXECUTABLE_DEBUG_DYLIB_INSTALL_NAME",
                        "EXECUTABLE_DEBUG_DYLIB_PATH"
                    )
                    environmentToFilter.forEach {
                        if (env.containsKey(it)) {
                            env.remove(it)
                        }
                    }
                    env.keys.filter {
                        it.startsWith("OTHER_") || it.startsWith("ASSETCATALOG_")
                    }.forEach {
                        env.remove(it)
                    }
                },
                outputMode = pipe,
            )

            parserProcessJob.join()
            if (xcodebuildResult.exitCode != 0) {
                userReadableError("xcodebuild failed with exit code ${xcodebuildResult.exitCode}")
            }
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
                            && "-target${DUMP_FILE_ARGS_SEPARATOR}${clangArchitecture}-apple" in clangArgs
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
                cinteropNamespace = "swiftPMImport.${module.userReadableName}".replace(Regex("[^a-zA-Z0-9]"), "."),
                discoverModulesImplicitly = true,
            )
        }

        targetFragments.map { it.platform }.toSet().forEach { platform ->
            val clangArchitecture = platform.clangArch
            val architectureSpecificProductLdCalls = ldArgsDump.listFilesOrEmpty().filter {
                it.isRegularFile()
            }.filter {
                val ldArgs = it.readLines().single()
                ("@rpath/lib${SYNTHETIC_IMPORT_DYLIB}.dylib" in ldArgs || "@rpath/${SYNTHETIC_IMPORT_DYLIB}.framework" in ldArgs)
                        && "-arch${DUMP_FILE_ARGS_SEPARATOR}${clangArchitecture}${DUMP_FILE_ARGS_SEPARATOR}" in ldArgs
            }

            if (architectureSpecificProductLdCalls.isEmpty()) {
                userReadableError("No linker calls discovered for architecture $clangArchitecture")
            }
            if (architectureSpecificProductLdCalls.size > 1) {
                userReadableError("Multiple linker calls discovered for ${platform.name}: ${architectureSpecificProductLdCalls.joinToString(", ")}")
            }

            val parsedLdCall = XcodebuildDefFileUtils.parseLdCall(architectureSpecificProductLdCalls.single())
            val outputPath = linkerArgumentsOutputPath(platform)
            outputPath.parent.createDirectories()
            outputPath.outputStream().use {
                @OptIn(ExperimentalSerializationApi::class)
                swiftPMJson.encodeToStream(parsedLdCall, it)
            }
        }
    }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        if (!fetchedPackage.path.exists()) {
            produces.forEach {
                it.path.deleteRecursively()
            }
            return EmptyTaskResult
        }

        val generatedPackagePath = generatedPackage.path
        val lockFile = generatedPackagePath.resolve("Package.resolved")
        val localPackageInputs = localPackageInputsArtifact.localPackageInputs?.let { it.sources + it.manifests } ?: emptyList()
        incrementalCache.executeForFiles(
            key = "package-dump-${module.userReadableName}-${platform.sdk}",
            inputValues = mapOf(
                "archs" to targetFragments.map { it.platform.clangArch }.sorted().joinToString(","),
            ),
            inputFiles = listOf(
                /**
                 * Don't track "workspace-state.json" here, it doesn't have stable ordering and mutates between resolved
                 * and xcodebuild calls. Also track just the contents instead and don't invalidate on mtime changes
                 */
                lockFile,
            ) + localPackageInputs,
        ) {
            spanBuilder("swiftPMImport").setAttribute("sdk", platform.sdk).setAmperModule(module).use {
                generateDefFilesAndLinkerDump(
                    syntheticImportProjectRoot = generatedPackage.path,
                    swiftPMDependenciesCheckout = fetchedPackage.path,
                )
                produces.map { it.path }.filter { it.exists() }
            }
        }

        return EmptyTaskResult
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
