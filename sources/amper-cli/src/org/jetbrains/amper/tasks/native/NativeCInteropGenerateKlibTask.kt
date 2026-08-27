/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.downloadNativeCompiler
import org.jetbrains.amper.compilation.serializableKotlinSettings
import org.jetbrains.amper.concurrency.mapConcurrently
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.engine.GenerateKlibsForIdeTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.stdlib.collections.distinctBy
import org.jetbrains.amper.stdlib.io.path.cleanDirectoryExcept
import org.jetbrains.amper.stdlib.io.path.createRegularFileIfNotExists
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.CinteropDefFileArtifact
import org.jetbrains.amper.tasks.artifacts.CinteropKlibsArtifact
import org.jetbrains.amper.tasks.artifacts.Selectors
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.util.BuildType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

internal class NativeCInteropGenerateKlibTask(
    override val module: AmperModule,
    override val platform: Platform,
    private val fragments: List<Fragment>,
    private val buildOutputRoot: AmperBuildOutputRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
    private val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
    private val ignorePlatformFailures: Boolean,
) : ArtifactTaskBase(), BuildTask, GenerateKlibsForIdeTask {
    init {
        require(platform.isLeaf) { "Expected a leaf platform, got: $platform" }
    }

    enum class MacroNamesCollectingMode {
        LEGACY,
        LIBCLANGEXT,
        LIBCLANGEXT_PARALLEL;

        val value: String
            get() = when (this) {
                LEGACY -> "legacy"
                LIBCLANGEXT -> "libclangext"
                LIBCLANGEXT_PARALLEL -> "libclangext_parallel"
            }

        companion object {
            val OPTION = "-Xmacro-collection-impl"
        }
    }

    private val leafFragment = module.leafFragments.first { it.platform == platform && !isTest }

    private val defFileArtifacts by Selectors.fromMatchingFragments(
        type = CinteropDefFileArtifact::class,
        module = module,
        isTest = false,
        hasPlatforms = setOf(platform),
        quantifier = Quantifier.AnyOrNone,
    )

    private val outputKlibsDirectoryArtifact by CinteropKlibsArtifact(
        buildOutputRoot = buildOutputRoot,
        module = module,
        platform = platform,
        path = leafFragment.generatedCinteropKlibsDirPath(buildOutputRoot.path)!!,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        class InputDefFile(
            val path: Path,
            val recommendedKotlinCompilerVersionOnFailingCinterop: ComparableVersion? = null,
            val macroNamesCollectingMode: MacroNamesCollectingMode? = null,
        )

        val inputDefFiles = buildList {
            defFileArtifacts.forEach {
                add(InputDefFile(it.path, it.recommendedKotlinCompilerVersionOnFailingCinterop, it.macroNamesCollectingMode))
            }
            for (fragment in fragments) {
                val path = fragment.cinteropPath?.takeIf { it.isDirectory() } ?: continue
                addAll(path.listDirectoryEntries("*.def").map { InputDefFile(it) })
            }
        }

        inputDefFiles.distinctBy(
            selector = { it.path.nameWithoutExtension },
            onDuplicates = { name, entries ->
                val conflictingPaths = entries.joinToString { it.path.absolutePathString() }
                userReadableError("Got multiple cinterop definitions with the name `$name`: $conflictingPaths")
            }
        )

        if (inputDefFiles.isEmpty()) {
            logger.debug("No .def files found, bailing out")
            outputKlibsDirectoryArtifact.path.deleteRecursively()
            return EmptyTaskResult
        }

        val targetLeafFragment = module.leafFragments.single { it.platform == platform && !it.isTest }
        val kotlinCompilerVersion = targetLeafFragment.serializableKotlinSettings().compilerVersion

        val results = inputDefFiles.mapConcurrently { defFile ->
            val cinteropName = defFile.path.nameWithoutExtension
            try {
                val includeDir = defFile.path.parent.resolve("include").takeIf { it.exists() }
                incrementalCache.executeForFiles(
                    key = "${taskName.id.value}-$cinteropName",
                    inputValues = mapOf(
                        "target" to platform.nameForCompiler,
                        "kotlinVersion" to kotlinCompilerVersion,
                        "macroNamesCollectingMode" to (defFile.macroNamesCollectingMode?.value ?: "none"),
                    ),
                    inputFiles = listOf(defFile.path) + listOfNotNull(includeDir),
                ) {
                    val outputKlib = outputKlibsDirectoryArtifact.path / module.cinteropKlibFileName(cinteropName)
                    outputKlib.createParentDirectories()

                    val nativeCompiler =
                        downloadNativeCompiler(kotlinCompilerVersion, userCacheRoot, jdkProvider)
                    val args = buildList {
                        add("-def")
                        add(defFile.path.pathString)
                        add("-target")
                        add(platform.nameForCompiler)
                        add("-o")
                        add(outputKlib.pathString)
                        // The klib name must be unique across the whole compilation classpath, and the bare cinterop
                        // name is not (it comes from a .def file name, which any dependency may also use).
                        add("-Xmodule-name")
                        add(module.cinteropKlibModuleName(cinteropName))
                        includeDir?.let {
                            add("-compiler-option")
                            add("-I${it.absolutePathString()}")
                        }
                        if (defFile.macroNamesCollectingMode != null && ComparableVersion(kotlinCompilerVersion) >= ComparableVersion("2.4.20-RC2")) {
                            add(MacroNamesCollectingMode.OPTION)
                            add(defFile.macroNamesCollectingMode.value)
                        }
                    }

                    logger.infoNoConsole("Running cinterop '$cinteropName' for platform '${platform.pretty}'...")
                    nativeCompiler.cinterop(processRunner, args, module)

                    [outputKlib]
                }.single().let { CinteropResult(it) }
            } catch (e: UserReadableError) {
                val message = buildString {
                    if (defFile.recommendedKotlinCompilerVersionOnFailingCinterop != null && ComparableVersion(kotlinCompilerVersion) < defFile.recommendedKotlinCompilerVersionOnFailingCinterop) {
                        appendLine("cinterop '${cinteropName}' should run with Kotlin version ${defFile.recommendedKotlinCompilerVersionOnFailingCinterop} or higher. Please update your Kotlin version.")
                    }
                    appendLine(e.message)
                }

                if (ignorePlatformFailures) {
                    logger.warn(message)
                    CinteropResult(
                        // A marker for the commonizer to include the failed target anyway
                        outputFile = (outputKlibsDirectoryArtifact.path /
                                "${module.cinteropKlibFileName(cinteropName)}.failed")
                            .createRegularFileIfNotExists(),
                        isSuccess = false,
                    )
                } else {
                    logger.error(message)
                    CinteropResult(null, isSuccess = false)
                }
            }
        }

        if (!ignorePlatformFailures && results.any { !it.isSuccess }) {
            userReadableError("cinterop processing failed for ${leafFragment.platform}, see the errors above")
        }

        // Clean any stale output Klibs
        val relevantOutputs = results.mapNotNull { it.outputFile }
        cleanDirectoryExcept(outputKlibsDirectoryArtifact.path, relevantOutputs)

        return Result(
            outputKlibsDirectoryArtifact.path,
            platform,
        )

    }

    private class CinteropResult(
        val outputFile: Path?,
        val isSuccess: Boolean = true,
    )

    data class Result(
        val path: Path,
        val platform: Platform,
    ) : TaskResult

    override val buildType: BuildType?
        get() = null

    override val isTest: Boolean
        get() = false

    companion object {
        private val logger = LoggerFactory.getLogger(NativeCInteropGenerateKlibTask::class.java)
    }
}
