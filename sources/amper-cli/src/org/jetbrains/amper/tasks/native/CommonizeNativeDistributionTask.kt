/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.downloadNativeCompiler
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.kotlin.native.asCommonizerTarget
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runJava
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.telemetry.setListAttribute
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

/**
 * Commonizer task that is used solely for IDE import.
 */
class CommonizeNativeDistributionTask(
    private val model: Model,
    private val userCacheRoot: AmperUserCacheRoot,
    private val tempRoot: AmperProjectTempRoot,
    private val incrementalCache: IncrementalCache,
    private val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
) : Task {
    companion object {
        val TASK_NAME = TaskName("commonizeNativeDistribution", "commonize native distribution")
    }

    override val taskName = TASK_NAME

    private val kotlinDownloader = KotlinArtifactsDownloader(userCacheRoot, incrementalCache)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        coroutineScope {
            model.nativePlatformSetsToCommonizeByKotlinVersion().forEach { [kotlinVersion, sharedPlatformSets] ->
                launch {
                    commonize(kotlinVersion, sharedPlatformSets)
                }
            }
        }
        return EmptyTaskResult
    }

    context(_: ProblemReporter)
    private suspend fun commonize(kotlinVersion: String, sharedPlatformSets: Set<List<Platform>>) {
        val sharedPlatforms = sharedPlatformSets.map { it.asCommonizerTarget() }.toSet()

        // TODO Maybe this should be separated into something more than a suspend function.
        val compiler = downloadNativeCompiler(kotlinVersion, userCacheRoot, jdkProvider)
        val commonizerClasspath = kotlinDownloader.downloadKotlinCommonizerEmbeddable(kotlinVersion)

        compiler.konanDistribution.commonizerCache.writeCacheForUncachedTargets(sharedPlatforms) { todoOutputTargets ->
            val commonizerArgs = buildList {
                add("native-dist-commonize")
                add("-distribution-path"); add(compiler.konanDistribution.homeDir.absolutePathString())
                add("-output-path"); add(compiler.konanDistribution.commonizedRoot.absolutePathString())
                add("-output-targets"); add(todoOutputTargets.joinToString(separator = ";") { it.targetNameForCompiler })
            }

            spanBuilder("kotlin-native-distribution-commonize")
                .setAttribute("compiler-version", kotlinVersion)
                .setListAttribute("commonizer-args", commonizerArgs)
                .use {
                    incrementalCache.execute(
                        key = "native-dist-commonize-$todoOutputTargets",
                        inputValues = mapOf("commonizerArgs" to commonizerArgs.joinToString()),
                        inputFiles = listOf(
                            compiler.konanDistribution.homeDir,
                            compiler.konanDistribution.commonizedRoot,
                            *commonizerClasspath.toTypedArray()
                        )
                    ) {
                        logger.info("Commonizing Kotlin/Native distribution...")
                        val result = processRunner.runJava(
                            jdk = compiler.jdk,
                            workingDir = Path("."),
                            mainClass = "org.jetbrains.kotlin.commonizer.cli.CommonizerCLI",
                            classpath = commonizerClasspath,
                            programArgs = commonizerArgs,
                            argsMode = ArgsMode.ArgFile(tempRoot = tempRoot),
                            outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger)),
                        )
                        if (result.exitCode != 0) {
                            userReadableError("Kotlin commonizer invocation failed (see errors above)")
                        }
                        return@execute IncrementalCache.ExecutionResult(emptyList())
                    }.outputFiles
                }
        }
    }

    private fun Model.nativePlatformSetsToCommonizeByKotlinVersion(): Map<String, Set<List<Platform>>> {
        val sharedPlatformSetsByKotlinVersion = mutableMapOf<String, MutableSet<List<Platform>>>()
        for (module in modules) {
            for (fragment in module.fragments) {
                val platforms = fragment.platforms.filter { it.isDescendantOf(Platform.NATIVE) }
                if (platforms.size > 1) {
                    val kotlinVersion = fragment.settings.kotlin.version
                    sharedPlatformSetsByKotlinVersion.getOrPut(kotlinVersion) { mutableSetOf() } += platforms.toList()
                }
            }
        }
        return sharedPlatformSetsByKotlinVersion
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
