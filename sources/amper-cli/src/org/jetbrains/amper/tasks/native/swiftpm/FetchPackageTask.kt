/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.cli.telemetry.setAmperModule
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.processes.LoggingProcessOutputListener
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.swiftpm.swiftPMJson
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.artifacts.ArtifactTaskBase
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import org.jetbrains.amper.tasks.artifacts.api.ArtifactSelector
import org.jetbrains.amper.tasks.artifacts.api.ArtifactType
import org.jetbrains.amper.tasks.artifacts.api.Quantifier
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.pathString

class FetchedSwiftPMPackage(
    override val path: Path,
    val module: AmperModule
) : Artifact {
    val workspaceStateJson: Path = path.resolve("workspace-state.json")
}

fun internalFetchedPackage(module: AmperModule) = ArtifactSelector(
    type = ArtifactType(FetchedSwiftPMPackage::class),
    predicate = { it.module == module },
    description = "SwiftPMImportPackage",
    quantifier = Quantifier.Single,
)

class FetchPackageTask(
    val module: AmperModule,
    taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
    override val taskName: TaskName,
): ArtifactTaskBase() {
    private val generatedPackage by generatedPackage<InternalSwiftPMImportPackage>(module)
    private val swiftPMDependenciesArtifact by swiftPMDependenciesArtifact(module)

    private val fetchedPackage by FetchedSwiftPMPackage(
        path = taskOutputRoot.path.resolve("swiftPMCheckout"),
        module = module,
    )

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val syntheticImportProjectRoot = generatedPackage.path
        if (!syntheticImportProjectRoot.exists()) {
            produces.single().path.deleteRecursively()
            return EmptyTaskResult
        }

        val swiftPMDependencies = swiftPMDependenciesArtifact.swiftPMDependencies
        val localSwiftPMDependencies = swiftPMDependencies.localPackageDependencies
        val swiftPMDependenciesCheckout = fetchedPackage.path

        incrementalCache.executeForFiles(
            key = "package-fetch-${module.userReadableName}",
            inputValues = mapOf(
                "packageGraph" to swiftPMJson.encodeToString(swiftPMDependencies),
                // FIXME: Fingerprint Xcode version everywhere
            ),
            inputFiles = localSwiftPMDependencies.map {
                it.absolutePath.resolve("Package.swift")
            },
        ) {
            spanBuilder("fetch package").setAmperModule(module).use {
                fetchPackage(
                    syntheticImportProjectRoot = syntheticImportProjectRoot,
                    swiftPMDependenciesCheckout = swiftPMDependenciesCheckout,
                )
                val lockFile = syntheticImportProjectRoot.resolve("Package.resolved")
                listOfNotNull(
                    // Lock file is always created adjacent to the Package.swift, but only on 1+ remote dependencies
                    if (lockFile.exists()) lockFile else null,
                    /**
                     * FIXME: "workspace-state.json" doesn't have stable ordering, so this step will be invalidated by the
                     * xcodebuild call. Here we primarily track it for correctness, so that if the checkout is manually
                     * removed we recheckout before running parallel xcodebuilds
                     *
                     * Package checkouts can be huge, just track "workspace-state.json" as a proxy for checkout's existence
                     */
                    fetchedPackage.workspaceStateJson,
                )
            }
        }
        return EmptyTaskResult
    }

    private suspend fun fetchPackage(
        syntheticImportProjectRoot: Path,
        swiftPMDependenciesCheckout: Path,
    ) {
        var result: ProcessResult
        var retryCount = 0
        do {
            var raceDownloadFailureHappened = false
            result = runProcess(
                workingDir = syntheticImportProjectRoot,
                configureEnvironment = {
                    val environmentToFilter = listOf("SDKROOT")
                    environmentToFilter.forEach { key ->
                        if (it.containsKey(key)) {
                            it.remove(key)
                        }
                    }
                },
                command = [
                    "/usr/bin/swift",
                    "package",
                    "--scratch-path", swiftPMDependenciesCheckout.pathString,
                    "resolve",
                ],
                outputMode = ProcessOutputMode.listen(
                    LoggingProcessOutputListener(
                        logger,
                        "SwiftPM import fetch: ",
                        stdoutLoggingLevel = Level.DEBUG,
                        stderrLoggingLevel = Level.DEBUG,
                    ) + object : ProcessOutputListener {
                        override fun onStdoutLine(line: String, pid: Long) {
                            // SwiftPM doesn't output logs to stdout
                        }

                        override fun onStderrLine(line: String, pid: Long) {
                            printErrorLine(line)
                        }

                        private fun printErrorLine(line: String) {
                            val trimmedLine = line.trim()
                            val errors = listOf("error:", "fatal:")
                            if (errors.any { trimmedLine.startsWith(it) }) {
                                if ("already exists in file system" in trimmedLine) {
                                    raceDownloadFailureHappened = true
                                }
                                logger.error(line)
                            }
                            val warnings = listOf("warning:")
                            if (warnings.any { trimmedLine.startsWith(it) }) {
                                logger.warn(line)
                            }
                        }
                    }
                )
            )
            if (result.exitCode == 0) {
                // Good path, exit cleanly
                return
            }
            if (raceDownloadFailureHappened) {
                // There is a flaky issue inside swiftPM resolve itself
                // See https://github.com/swiftlang/swift-package-manager/issues/10138
                logger.warn("Retrying fetching SwiftPM dependencies")
                retryCount++
            } else {
                // No flaky failure detected, no sense in retrying, abort immediately
                break
            }
        } while (retryCount < MAX_SPM_FETCH_RETRY_COUNT)

        if (result.exitCode != 0) {
            userReadableError("SwiftPM fetch failed, see errors above")
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}

private const val MAX_SPM_FETCH_RETRY_COUNT = 5