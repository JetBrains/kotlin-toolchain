/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.web

import kotlinx.serialization.json.Json
import org.jetbrains.amper.ProcessRunner
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.tasks.ResolveExternalDependenciesTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.native.filterKLibs
import org.jetbrains.amper.tasks.web.NpmInstallTask.Companion.json
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.pathString
import kotlin.io.path.writeText

/**
 * Collects all klib dependencies, inspects each for a `package.json` file,
 * merges all discovered NPM dependencies into a single `package.json`,
 * and installs them using pnpm.
 */
class NpmInstallTask(
    val module: AmperModule,
    val platform: Platform,
    private val taskOutputPath: TaskOutputRoot,
    override val taskName: TaskName,
    private val processRunner: ProcessRunner,
    private val userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
) : Task {

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val runtimeClasspath = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .flatMap { it.runtimeClasspath }
            .distinct()
            .filterKLibs()

        val nodeModulesPath = incrementalCache.executeForFiles(
            "${taskName.id.value}-extract-npm-dependencies",
            inputValues = emptyMap(),
            inputFiles = runtimeClasspath,
        ) {
            val uniqueNpmDependencies = extractAllNpmDependencies(runtimeClasspath, logger::warn)

            if (uniqueNpmDependencies.isEmpty()) {
                logger.debug("No npm dependencies found in klib files for module '${module.userReadableName}'")
                return@executeForFiles emptyList()
            }

            val outputDir = taskOutputPath.path.createDirectories()

            incrementalCache.executeForFiles(
                "${taskName.id.value}-install-npm-dependencies",
                inputFiles = emptyList(),
                inputValues = uniqueNpmDependencies
            ) {
                val packageJsonString = buildPackageJson(
                    module.userReadableName,
                    uniqueNpmDependencies,
                )

                val packageJsonPath = taskOutputPath.path.resolve(PACKAGE_JSON).also {
                    it.writeText(
                        packageJsonString
                    )
                }

                logger.debug("Generated package.json with ${uniqueNpmDependencies.size} npm dependencies at $packageJsonPath")

                val nodeExecutable = downloadNodeJs(userCacheRoot)
                val pnpmMjs = downloadPnpm(userCacheRoot)

                spanBuilder("pnpm install")
                    .use {
                        val disableUpdateNotify = processRunner.runProcess(
                            workingDir = outputDir,
                            command = [
                                nodeExecutable.pathString,
                                pnpmMjs.pathString,
                                "config",
                                "set",
                                "--location=project",
                                "updateNotifier",
                                "false"
                            ],
                            span = it,
                            outputMode = ProcessOutputMode.listenAndCaptureStderr(
                                listener = LoggingProcessOutputListener(logger),
                            ),
                        )

                        if (disableUpdateNotify.exitCode != 0) {
                            error(
                                "pnpm configuration exits with the code ${disableUpdateNotify.exitCode}:\n" +
                                        disableUpdateNotify.stderr
                            )
                        }

                        val result = processRunner.runProcess(
                            workingDir = outputDir,
                            command = [
                                nodeExecutable.pathString,
                                pnpmMjs.pathString,
                                "install"
                            ],
                            span = it,
                            outputMode = ProcessOutputMode.listenAndCaptureStderr(
                                listener = LoggingProcessOutputListener(logger),
                            ),
                        )
                        if (result.exitCode != 0) {
                            error("pnpm install failed with exit code ${result.exitCode}:\n${result.stderr}")
                        }
                    }

                logger.info("pnpm install completed successfully")

                listOf(packageJsonPath.resolveSibling(NODE_MODULES))
            }
        }

        return Result(
            nodeModulesPath = nodeModulesPath.singleOrNull(),
        )
    }

    internal companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }

    class Result(
        val nodeModulesPath: Path?,
    ) : TaskResult

    private val logger = LoggerFactory.getLogger(javaClass)
}

internal fun extractAllNpmDependencies(
    runtimeClasspath: List<Path>,
    onWarn: (String) -> Unit,
): Map<String, String> {
    val resultedDependencies = mutableMapOf<String, NpmVersionCandidate>()

    runtimeClasspath
        .flatMap { klibPath ->
            readPackageJsonDependenciesFromKlib(klibPath)
        }
        .forEach { (name, versionCandidate) ->
            val existing = resultedDependencies[name]

            if (existing != null && existing.version != versionCandidate.version) {
                onWarn(
                    "Conflicting npm dependency versions for '$name': " +
                            "'${existing.version}' (from ${existing.klibPath} vs " +
                            "'${versionCandidate.version}' (from ${versionCandidate.klibPath}). " +
                            "Using '${versionCandidate.version}'."
                )
            }
            resultedDependencies[name] = versionCandidate
        }

    return resultedDependencies
        .mapValues { [_, value] -> value.version }
}

/**
 * Reads the `package.json` file from inside a klib and returns the `dependencies` with information about source klib
 */
private fun readPackageJsonDependenciesFromKlib(klibPath: Path): List<NpmDependencyCandidate> {
    ZipFile(klibPath.toFile()).use { zip ->
        val packageJsonEntry = zip.entries().asSequence()
            .firstOrNull { it.name == "package.json" }
            ?: return emptyList()

        val content = zip.getInputStream(packageJsonEntry).bufferedReader().readText()

        val packageJson = json.decodeFromString<PackageJson>(content)

        return packageJson.dependencies
            .map { [name, version] ->
                NpmDependencyCandidate(
                    name,
                    NpmVersionCandidate(version, klibPath)
                )
            }
    }
}

internal fun buildPackageJson(
    moduleName: String,
    dependencies: Map<String, String>,
): String {
    val packageJson = PackageJson(
        name = moduleName,
        version = "1.0.0",
        private = true,
        dependencies = dependencies,
    )

    return json.encodeToString(packageJson)
}

private class NpmDependencyCandidate(val name: String, val versionCandidate: NpmVersionCandidate)

private class NpmVersionCandidate(val version: String, val klibPath: Path)