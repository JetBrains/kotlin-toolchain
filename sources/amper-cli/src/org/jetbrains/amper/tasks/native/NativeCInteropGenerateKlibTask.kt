/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

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
        val inputDefFiles = buildList {
            defFileArtifacts.forEach {
                add(it.path)
            }
            for (fragment in fragments) {
                val path = fragment.cinteropPath?.takeIf { it.isDirectory() } ?: continue
                addAll(path.listDirectoryEntries("*.def"))
            }
        }

        inputDefFiles.distinctBy(
            selector = { it.nameWithoutExtension },
            onDuplicates = { name, entries ->
                val conflictingPaths = entries.joinToString { it.absolutePathString() }
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
            val cinteropName = defFile.nameWithoutExtension
            try {
                val includeDir = defFile.parent.resolve("include").takeIf { it.exists() }
                incrementalCache.executeForFiles(
                    key = "${taskName.id.value}-$cinteropName",
                    inputValues = mapOf(
                        "target" to platform.nameForCompiler,
                        "kotlinVersion" to kotlinCompilerVersion,
                    ),
                    inputFiles = listOf(defFile) + listOfNotNull(includeDir),
                ) {
                    val outputKlib = outputKlibsDirectoryArtifact.path / module.cinteropKlibFileName(cinteropName)
                    outputKlib.createParentDirectories()

                    val nativeCompiler =
                        downloadNativeCompiler(kotlinCompilerVersion, userCacheRoot, jdkProvider)
                    val args = buildList {
                        add("-def")
                        add(defFile.pathString)
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
                    }

                    logger.infoNoConsole("Running cinterop '$cinteropName' for platform '${platform.pretty}'...")
                    nativeCompiler.cinterop(processRunner, args)

                    [outputKlib]
                }.single().let { CinteropResult(it) }
            } catch (e: UserReadableError) {
                if (ignorePlatformFailures) {
                    logger.warn(e.message)
                    CinteropResult(
                        // A marker for the commonizer to include the failed target anyway
                        outputFile = (outputKlibsDirectoryArtifact.path /
                                "${module.cinteropKlibFileName(cinteropName)}.failed")
                            .createRegularFileIfNotExists(),
                        isSuccess = false,
                    )
                } else {
                    logger.error(e.message)
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
