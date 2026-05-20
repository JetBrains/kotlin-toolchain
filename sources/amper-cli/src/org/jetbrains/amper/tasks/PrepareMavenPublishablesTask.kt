/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.crypto.pgp.AsciiArmoredPgpKey
import org.jetbrains.amper.crypto.pgp.PgpKeyParsingException
import org.jetbrains.amper.crypto.pgp.PgpSigner
import org.jetbrains.amper.crypto.pgp.PgpSigningException
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.TaskName
import org.jetbrains.amper.frontend.isArtifactSigningEnabled
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForSerializable
import org.jetbrains.amper.incrementalcache.getDynamicInputs
import org.jetbrains.amper.maven.publish.PublicationCoordinatesOverrides
import org.jetbrains.amper.maven.publish.merge
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.maven.publish.writePomFor
import org.jetbrains.amper.serialization.paths.SerializablePath
import org.jetbrains.amper.tasks.jvm.JvmClassesJarTask
import org.jetbrains.amper.tasks.native.NativeCompileKlibTask
import org.jetbrains.amper.tasks.web.WebCompileKlibTask
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class PrepareMavenPublishablesTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
) : Task {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override suspend fun run(dependenciesResult: List<TaskResult>, executionContext: TaskGraphExecutionContext): TaskResult {
        val depsCoordinatesOverrides = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .map { it.coordinateOverridesForPublishing }
            .merge()

        val platforms = if (module.leafPlatforms.size > 1) module.leafPlatforms + Platform.COMMON else module.leafPlatforms
        val coordsPerPlatform = platforms.associateWith { module.publicationCoordinates(platform = it) }
        val modulePublishablesFromOtherTasks = dependenciesResult.flatMap { it.toMavenPublishables(coordsPerPlatform) }

        val signingEnabled = module.isArtifactSigningEnabled()

        val publishables = incrementalCache.executeForSerializable<List<MavenPublishable>>(
            key = taskName.name,
            inputValues = mapOf(
                "platforms" to platforms.joinToString(),
                "depsCoordinatesOverrides" to Json.encodeToString(depsCoordinatesOverrides),
                "coordsPerPlatform" to Json.encodeToString(coordsPerPlatform),
                "modulePublishablesFromOtherTasks" to Json.encodeToString(modulePublishablesFromOtherTasks),
                "signingEnabled" to signingEnabled.toString(),
                "moduleInfo" to Json.encodeToString(listOf(module.userReadableName, module.description)),
            ),
            inputFiles = modulePublishablesFromOtherTasks.map { it.path },
        ) {
            taskOutputRoot.path.deleteRecursively()
            taskOutputRoot.path.createDirectories()

            assertNoDirectories(modulePublishablesFromOtherTasks)

            val poms = coordsPerPlatform.map { (platform, coords) ->
                generatePomFile(module, platform, depsCoordinatesOverrides).toMavenPublishable(coords)
            }
            val meaningfulPublishables = poms + modulePublishablesFromOtherTasks
            if (signingEnabled) {
                val artifactSigner = createSignerFromEnvConfig()
                val signatures = meaningfulPublishables.map { artifactSigner.signArtifact(it) }
                meaningfulPublishables + signatures
            } else {
                meaningfulPublishables
            }
        }

        return Result(publishables)
    }

    private fun assertNoDirectories(publishables: List<MavenPublishable>) {
        val directoryPublishables = publishables.filter { it.path.isDirectory() }
        if (directoryPublishables.isNotEmpty()) {
            error("The following publishables point to a directory, but Maven publishing only accepts files " +
                    "as artifacts:\n${directoryPublishables.joinToString("\n") { " - $it" }}")
        }
    }

    private fun generatePomFile(
        module: AmperModule,
        platform: Platform,
        overrides: PublicationCoordinatesOverrides,
    ): Path {
        val tempPath = taskOutputRoot.path.resolve("${module.userReadableName}-${platform.pretty}.pom")
        // TODO publish Gradle metadata
        tempPath.writePomFor(module, platform, overrides, gradleMetadataComment = false)
        return tempPath
    }

    // We currently only support providing the signing key via environment variables.
    // We will later add a mechanism that will allow defining custom properties or env vars, and thus specify the key
    // on a per-module basis.
    private suspend fun createSignerFromEnvConfig(): PgpSigner = try {
        PgpSigner.bouncyCastle(
            signingKey = getDynamicInputs().readEnv("KOTLIN_TOOLCHAIN_SIGNING_KEY")?.let(::AsciiArmoredPgpKey)
                ?: userReadableError(
                    "Artifact signing is enabled, but the KOTLIN_TOOLCHAIN_SIGNING_KEY environment variable is not provided. " +
                            "Please set this variable to a valid PGP private key in ASCII-armored format."
                ),
            keyPassphrase = getDynamicInputs().readEnv("KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE")?.toCharArray(),
        )
    } catch (e: PgpKeyParsingException) {
        userReadableError("Cannot sign artifacts, failed to parse PGP signing key from the KOTLIN_TOOLCHAIN_SIGNING_KEY " +
                "environment variable: ${e.message}")
    }

    private suspend fun PgpSigner.signArtifact(artifact: MavenPublishable): MavenPublishable {
        val signatureFilePath = taskOutputRoot.path.resolve(artifact.path.name + ".asc")
        try {
            logger.info("Signing artifact '${artifact.path.name}'…")
            sign(artifact.path, outputSignatureFile = signatureFilePath)
        } catch (e: PgpSigningException) {
            userReadableError("PGP signing failed for artifact '${artifact.path.name}': ${e.message}")
        }
        return MavenPublishable(
            coordinates = artifact.coordinates,
            mavenArtifactExtension = artifact.mavenArtifactExtension + ".asc",
            path = signatureFilePath,
        )
    }

    class Result(val publishables: List<MavenPublishable>) : TaskResult
}

@Serializable
data class MavenPublishable(
    /**
     * The Maven coordinates to use when publishing.
     */
    val coordinates: MavenCoordinates,
    /**
     * The file extension of the artifact as it should appear in the Maven publication, without the leading dot.
     */
    val mavenArtifactExtension: String,
    /**
     * The path to the publishable file.
     */
    val path: SerializablePath,
)

private fun TaskResult.toMavenPublishables(coordsPerPlatform: Map<Platform, MavenCoordinates>) = when (this) {
    is JvmClassesJarTask.Result -> listOf(toMavenPublishable(coordsPerPlatform))
    is SourcesJarTask.Result -> listOf(toMavenPublishable(coordsPerPlatform))
    is JavadocJarTask.Result -> listOf(toMavenPublishable(coordsPerPlatform))
    is NativeCompileKlibTask.Result -> toMavenPublishables(coordsPerPlatform)
    is WebCompileKlibTask.Result -> toMavenPublishables(coordsPerPlatform)
    is ResolveExternalDependenciesTask.Result -> emptyList() // this is just for coords overrides, not extra artifacts
    else -> error("Unsupported dependency result: ${javaClass.name}")
}

private fun JvmClassesJarTask.Result.toMavenPublishable(coordsPerPlatform: Map<Platform, MavenCoordinates>): MavenPublishable =
    jarPath.toMavenPublishable(coordsPerPlatform.getValue(Platform.JVM))

private fun SourcesJarTask.Result.toMavenPublishable(coordsPerPlatform: Map<Platform, MavenCoordinates>): MavenPublishable =
    jarPath.toMavenPublishable(coordsPerPlatform.getValue(platform).copy(classifier = "sources"))

private fun JavadocJarTask.Result.toMavenPublishable(coordsPerPlatform: Map<Platform, MavenCoordinates>): MavenPublishable =
    jarPath.toMavenPublishable(coordsPerPlatform.getValue(platform).copy(classifier = "javadoc"))

private fun NativeCompileKlibTask.Result.toMavenPublishables(
    coordsPerPlatform: Map<Platform, MavenCoordinates>
): List<MavenPublishable> = listOfNotNull(compiledKlib?.toMavenPublishable(coordsPerPlatform.getValue(platform)))

private fun WebCompileKlibTask.Result.toMavenPublishables(
    coordsPerPlatform: Map<Platform, MavenCoordinates>
): List<MavenPublishable> = listOfNotNull(compiledKlib?.toMavenPublishable(coordsPerPlatform.getValue(platform)))

private fun Path.toMavenPublishable(
    coords: MavenCoordinates,
    extension: String = this.extension,
): MavenPublishable = MavenPublishable(
    coordinates = coords,
    mavenArtifactExtension = extension,
    path = this,
)
