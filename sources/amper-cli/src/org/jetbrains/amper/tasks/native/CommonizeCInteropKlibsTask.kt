/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.compilation.KotlinArtifactsDownloader
import org.jetbrains.amper.compilation.downloadNativeCompiler
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.GenerateKlibsForIdeTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.kotlin.native.CommonizerTarget
import org.jetbrains.amper.kotlin.native.asCommonizerTarget
import org.jetbrains.amper.kotlin.native.dependencyLibrariesForCommonization
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.processes.ArgsMode
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runJava
import org.jetbrains.amper.stdlib.io.path.cleanDirectoryExcept
import org.jetbrains.amper.stdlib.io.path.listDirectoryEntriesIfExistsOrEmpty
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.CinteropCommonizedKlibArtifact
import org.jetbrains.amper.tasks.artifacts.CinteropKlibsArtifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactType
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class CommonizeCInteropKlibsTask(
    buildOutputRoot: AmperBuildOutputRoot,
    private val userCacheRoot: AmperUserCacheRoot,
    private val tempRoot: AmperProjectTempRoot,
    private val incrementalCache: IncrementalCache,
    private val jdkProvider: JdkProvider,
    private val processRunner: ProcessRunner,
    override val taskName: TaskName,
    val module: AmperModule,
) : ArtifactTaskBase(), GenerateKlibsForIdeTask {
    private val kotlinDownloader = KotlinArtifactsDownloader(userCacheRoot, incrementalCache)
    private val kotlinVersion = module.fragments.first().settings.kotlin.version

    val cinteropKlibs by ArtifactSelector(
        type = ArtifactType(CinteropKlibsArtifact::class),
        predicate = { it.module == module },
        description = "All cinterop klibs from ${module.userReadableName}",
        quantifier = Quantifier.AnyOrNone,
    )

    val output by CinteropCommonizedKlibArtifact(
        buildOutputRoot = buildOutputRoot,
        module = module,
    )

    private data class CinteropKlib(
        /** `null` if there was an error generating this klib. */
        val klibPath: Path?,
        val name: String,
        val leafPlatform: Platform,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): TaskResult {
        // cinterop "name" -> all the corresponding klibs from all the leaf platforms.
        val allKlibsFlat = cinteropKlibs.flatMap { artifact ->
            artifact.path.listDirectoryEntriesIfExistsOrEmpty().map {
                CinteropKlib(
                    name = it.name.substringBefore('.'),
                    klibPath = it.takeIf { it.extension == "klib" },
                    leafPlatform = artifact.platform,
                )
            }
        }

        // The Kotlin version can only be configured for the whole module, so every fragment uses the same one.
        // Therefore, it is safe to take the version from an arbitrary first fragment.
        // TODO: Cluster sets of commonization targets if they are non-intersecting?
        //  this way there is no benefit of not re-reading klibs (no shared klibs)
        //  and there might be a benefit of incremental granularity.
        val relevantOutputs = commonize(allKlibsFlat)

        cleanDirectoryExcept(output.path, keepPaths = relevantOutputs)

        return EmptyTaskResult
    }

    context(_: ProblemReporter)
    private suspend fun commonize(
        klibs: List<CinteropKlib>,
    ): List<Path> {

        val groupedByName: Map<String, List<CinteropKlib>> = klibs.groupBy(
            keySelector = { it.name },
        ).filter { [_, libs] ->
            // Only include those entries where there are multiple klibs for one name;
            // otherwise there is nothing to commonize.
            libs.size > 1
        }

        if (groupedByName.isEmpty()) {
            logger.debug("No relevant .klibs found to commonize, bailing out")
            return []
        }

        val compiler = downloadNativeCompiler(kotlinVersion, userCacheRoot, jdkProvider)
        val commonizerClasspath = kotlinDownloader.downloadKotlinCommonizerEmbeddable(kotlinVersion)

        val [targets: Set<CommonizerTarget>, outputs: Set<Path>] = run {
            val targets = mutableSetOf<CommonizerTarget>()
            val outputs = mutableSetOf<Path>()

            groupedByName.forEach { [name, libs] ->
                val platforms = libs.mapTo(mutableSetOf()) { it.leafPlatform }
                val commonizerTargets = commonizerTargetsFor(platforms)
                targets.addAll(commonizerTargets)

                commonizerTargets.forEach {
                    outputs.add(output.path / it.dirName / name)
                }
            }

            targets to outputs
        }

        val dependenciesString = targets
            .flatMap {
                // We depend on the task that populates the Kotlin/Native distribution's cache, so this is ok
                // TODO: Need to prepend the target here like in KGP?
                compiler.konanDistribution
                    .dependencyLibrariesForCommonization(it)
            }.distinct()
            .joinToString(";") { it.absolutePathString() }

        val inputLibraries = klibs
            .mapNotNull { it.klibPath }

        if (inputLibraries.isEmpty()) {
            // Can happen at this stage if every cinterop processing failed
            return []
        }

        val inputLibrariesString = inputLibraries
            .joinToString(";") { it.absolutePathString() }

        return incrementalCache.executeForFiles(
            key = "${taskName.id.value}-$kotlinVersion",
            inputValues = mapOf(
                "outputs" to outputs.joinToString(";") { it.absolutePathString() },
                "inputLibraries" to inputLibrariesString,
                "dependencyLibraries" to dependenciesString,
            ),
            inputFiles = buildList {
                add(compiler.konanDistribution.homeDir)
                klibs.mapNotNullTo(this) { it.klibPath }
                addAll(inputLibraries)
                addAll(commonizerClasspath)
            }
        ) {
            output.path.createDirectories()

            logger.debug("Commonizing from ${klibs.size} klibs...")
            // NOTE: commonizer takes care of grouping the relevant klibs itself
            val commonizerArgs: MutableList<String> = [
                "native-klib-commonize",
                "-distribution-path",
                compiler.konanDistribution.homeDir.absolutePathString(),
                "-output-path",
                output.path.absolutePathString(),
                "-input-libraries",
                inputLibrariesString,
                "-output-targets",
                targets.joinToString(";") { it.targetNameForCompiler },
            ]

            if (dependenciesString.isNotBlank()) {
                commonizerArgs += "-dependency-libraries"
                commonizerArgs += dependenciesString
            }

            val result = processRunner.runJava(
                jdk = compiler.jdk,
                workingDir = Path("."),
                mainClass = "org.jetbrains.kotlin.commonizer.cli.CommonizerCLI",
                classpath = commonizerClasspath,
                programArgs = commonizerArgs,
                argsMode = ArgsMode.ArgFile(tempRoot = tempRoot),
                outputMode = ProcessOutputMode.listen(LoggingProcessOutputListener(logger = logger)),
            )
            if (result.exitCode != 0) {
                userReadableError("cinterop commonization failed, see the errors above")
            }
            outputs.forEach {
                check(it.isDirectory()) { "Expected `$it` to exist after commonization" }
            }
            outputs.toList()
        }
    }

    /** All the commonizer targets from all the matching fragments for the given set of platforms */
    private fun commonizerTargetsFor(platforms: Set<Platform>): Set<CommonizerTarget> {
        return module.fragments
            .filter { !it.isTest && it.cinteropPath != null && it.platforms.size > 1 }
            .map { it.platforms }
            .distinct()
            .filter { fragmentPlatforms -> platforms.containsAll(fragmentPlatforms) }
            .mapTo(mutableSetOf()) { it.asCommonizerTarget() }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}