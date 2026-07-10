/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.publication

import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.engine.PackageTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForFiles
import org.jetbrains.amper.jar.ZipConfig
import org.jetbrains.amper.jar.ZipInput
import org.jetbrains.amper.jar.writeZip
import org.jetbrains.amper.tasks.MavenPublishable
import org.jetbrains.amper.tasks.PrepareMavenPublishablesTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories

class PrepareMavenCentralBundleTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    private val incrementalCache: IncrementalCache,
    private val taskOutputRoot: TaskOutputRoot,
) : PackageTask {
    override val format: PackageTask.Format = PackageTask.Format.MavenCentralBundle
    override val platform: Platform? = null // we bundle all platforms together
    override val buildType: BuildType? = null

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(
        dependenciesResult: List<TaskResult>,
    ): Result {
        val allPublishables = dependenciesResult.filterIsInstance<PrepareMavenPublishablesTask.Result>()
            .singleOrNull()
            ?.publishables
            ?: error("Expected single PrepareMavenPublishablesTask.Result from task dependencies")

        val zip = incrementalCache.executeForFiles(
            key = "${taskName.id.value}-zip-bundle",
            inputValues = mapOf("publishables" to Json.encodeToString(allPublishables)),
            inputFiles = allPublishables.map { it.path },
        ) {
            val zipInputs = allPublishables.map { it.toZipInput() }
            val zipOutputPath = taskOutputRoot.path.resolve("${module.userReadableName}-central-bundle.zip").createParentDirectories()
            zipOutputPath.writeZip(zipInputs, ZipConfig())
            listOf(zipOutputPath)
        }.single()

        return Result(zip)
    }

    class Result(val mavenCentralZipBundle: Path): TaskResult
}

private fun MavenPublishable.toZipInput(): ZipInput = ZipInput(
    path = path,
    destPathInArchive = coordinates.toMavenCentralZipPath(mavenArtifactExtension),
)

private fun MavenCoordinates.toMavenCentralZipPath(extension: String): Path {
    val artifactFilename = buildString {
        append(artifactId)
        append('-')

        val nonNullVersion = version ?: error("Missing 'version' in MavenPublishable coordinates: $this")
        if ("SNAPSHOT" in nonNullVersion) {
            userReadableError("SNAPSHOT versions are not supported on Maven Central. Cannot publish $this")
        }
        // No need for SNAPSHOT transformation here (<version>-<timestamp>-<build>) because Maven Central does not
        // support SNAPSHOT versions anyway.
        append(nonNullVersion)

        if (classifier != null) {
            append('-')
            append(classifier)
        }
        append('.')
        append(extension)
    }
    return Path("${groupId.replace('.', '/')}/$artifactId/$version/$artifactFilename")
}
