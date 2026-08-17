/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.concurrency.flatMapConcurrently
import org.jetbrains.amper.concurrency.mapConcurrently
import org.jetbrains.amper.crypto.pgp.AsciiArmoredPgpKey
import org.jetbrains.amper.crypto.pgp.PgpKeyParsingException
import org.jetbrains.amper.crypto.pgp.PgpSigner
import org.jetbrains.amper.crypto.pgp.PgpSigningException
import org.jetbrains.amper.crypto.pgp.PgpSigningKeyPassphraseException
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isArtifactSigningEnabled
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.schema.Checksum
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForSerializable
import org.jetbrains.amper.incrementalcache.getDynamicInputs
import org.jetbrains.amper.maven.publish.PublicationCoordinatesOverrides
import org.jetbrains.amper.maven.publish.isMultiplatformPublication
import org.jetbrains.amper.maven.publish.merge
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.maven.publish.writePomFor
import org.jetbrains.amper.serialization.paths.SerializablePath
import org.jetbrains.amper.stdlib.hashing.hash
import org.jetbrains.amper.tasks.android.AndroidAarTask
import org.jetbrains.amper.tasks.jvm.JvmClassesJarTask
import org.jetbrains.amper.tasks.metadata.AssembleAllMetadataTask
import org.jetbrains.amper.tasks.metadata.cinteropClassifier
import org.jetbrains.amper.tasks.metadata.generateCommonGradleModuleMetadata
import org.jetbrains.amper.tasks.metadata.generateGradleMetadataForLeafPlatform
import org.jetbrains.amper.tasks.metadata.isCinteropClassifier
import org.jetbrains.amper.tasks.metadata.kotlinToolingMetadataFor
import org.jetbrains.amper.tasks.metadata.readKlibAbiVersion
import org.jetbrains.amper.tasks.native.NativeCInteropGenerateKlibTask
import org.jetbrains.amper.tasks.native.NativeCompileKlibTask
import org.jetbrains.amper.tasks.web.WebCompileKlibTask
import org.jetbrains.kotlin.tooling.metadata.KOTLIN_TOOLING_METADATA_CLASSIFIER
import org.jetbrains.kotlin.tooling.metadata.KotlinToolingMetadata
import org.jetbrains.kotlin.tooling.metadata.writeTo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

class PrepareMavenPublishablesTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
) : Task {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val depsCoordinatesOverrides = dependenciesResult
            .filterIsInstance<ResolveExternalDependenciesTask.Result>()
            .map { it.coordinateOverridesForPublishing }
            .merge()

        val platforms = if (module.isMultiplatformPublication()) module.leafPlatforms + Platform.COMMON else module.leafPlatforms
        val coordsPerPlatform = platforms.associateWith { module.publicationCoordinates(platform = it) }
        val modulePublishablesFromOtherTasks = dependenciesResult.flatMap { it.toMavenPublishables(coordsPerPlatform) }

        val signingEnabled = module.isArtifactSigningEnabled()

        // Only multiplatform libraries describe themselves with tooling metadata, just like in Gradle builds.
        val toolingMetadata = if (module.isMultiplatformPublication()) {
            kotlinToolingMetadataFor(
                module = module,
                konanAbiVersion = findKonanAbiVersion(coordsPerPlatform, modulePublishablesFromOtherTasks),
            )
        } else null

        val publishables = incrementalCache.executeForSerializable<List<MavenPublishable>>(
            key = taskName.id.value,
            inputValues = mapOf(
                "platforms" to platforms.joinToString(),
                "depsCoordinatesOverrides" to Json.encodeToString(depsCoordinatesOverrides),
                "coordsPerPlatform" to Json.encodeToString(coordsPerPlatform),
                "modulePublishablesFromOtherTasks" to Json.encodeToString(modulePublishablesFromOtherTasks),
                "signingEnabled" to signingEnabled.toString(),
                "moduleInfo" to Json.encodeToString(listOf(module.userReadableName, module.description)),
                "checksums" to module.publishingSettings.checksums.joinToString(),
                "publishSources" to module.publishingSettings.publishSources.toString(),
                "toolingMetadata" to toolingMetadata?.let { Json.encodeToString(it) }.orEmpty(),
            ),
            inputFiles = modulePublishablesFromOtherTasks.map { it.path },
        ) {
            taskOutputRoot.path.deleteRecursively()
            taskOutputRoot.path.createDirectories()

            assertNoDirectories(modulePublishablesFromOtherTasks)

            val poms = coordsPerPlatform.map { [platform, coords] ->
                generatePomFile(
                    module = module,
                    platform = platform,
                    overrides = depsCoordinatesOverrides,
                    hasMainArtifact = modulePublishablesFromOtherTasks.singleWithCoordinatesOrNull(coords) != null,
                ).toMavenPublishable(coords)
            }
            val meaningfulPublishables = mutableListOf<MavenPublishable>()
            meaningfulPublishables.addAll(poms)
            meaningfulPublishables.addAll(modulePublishablesFromOtherTasks)
            toolingMetadata?.let { meaningfulPublishables.add(generateToolingMetadataPublishable(it, coordsPerPlatform)) }

            val checksumsToPublish = module.publishingSettings.checksums
            val checksumPublishables = mutableMapOf<String, List<MavenPublishable>>()

            // Checksums are required for Gradle metadata generation, we have to calculate them at this point.
            checksumPublishables.putAll(meaningfulPublishables
                .flatMapConcurrently { listOf(it.path.toAbsolutePath().toString() to generateChecksums(it, checksumsToPublish)) }
                .toMap()
            )

            generateGradleMetadata(checksumPublishables, coordsPerPlatform, modulePublishablesFromOtherTasks, depsCoordinatesOverrides)
                .also {
                    meaningfulPublishables.addAll(it)
                    checksumPublishables.putAll(
                        it.flatMapConcurrently {
                            listOf(it.path.toAbsolutePath().toString() to generateChecksums(it, checksumsToPublish))
                        }.toMap()
                    )
                }

            // Checksums are not mandatory for the signature files themselves, and we want to limit the number
            // of published files, so we don't generate checksums for the signatures here.
            val meaningfulPublishablesWithSignatures = if (signingEnabled) {
                val artifactSigner = createSignerFromEnvConfig()
                val signatures = meaningfulPublishables.map { artifactSigner.signArtifact(it) }
                meaningfulPublishables + signatures
            } else {
                meaningfulPublishables
            }

            meaningfulPublishablesWithSignatures + checksumPublishables.values.flatten()
        }

        return Result(publishables)
    }

    /**
     * Reads the ABI version of the Kotlin/Native compiler from one of the klibs published for the native platforms of
     * this module, or returns null if this module publishes no native klib (a native module without sources, for
     * instance).
     *
     * All native platforms are compiled by the same compiler, so any of their klibs reports the same version.
     */
    private suspend fun findKonanAbiVersion(
        coordsPerPlatform: Map<Platform, MavenCoordinates>,
        modulePublishablesFromOtherTasks: List<MavenPublishable>,
    ): String? = coordsPerPlatform
        .filterKeys { it.isDescendantOf(Platform.NATIVE) }
        .values
        .firstNotNullOfOrNull { coords ->
            modulePublishablesFromOtherTasks
                .firstOrNull { it.coordinates == coords && it.mavenArtifactExtension == "klib" }
                ?.let { readKlibAbiVersion(it.path) }
        }

    /**
     * Writes the tooling metadata of this multiplatform library, and returns it as an artifact of the root
     * publication, with the classifier and extension that its consumers expect.
     *
     * Note: this artifact is not part of any Gradle metadata variant, exactly like in Gradle publications.
     */
    private suspend fun generateToolingMetadataPublishable(
        toolingMetadata: KotlinToolingMetadata,
        coordsPerPlatform: Map<Platform, MavenCoordinates>,
    ): MavenPublishable {
        val toolingMetadataFile = withContext(Dispatchers.IO) {
            toolingMetadata.writeTo(taskOutputRoot.path)
        }
        val coords = coordsPerPlatform.getValue(Platform.COMMON).copy(classifier = KOTLIN_TOOLING_METADATA_CLASSIFIER)
        return toolingMetadataFile.toMavenPublishable(coords)
    }

    private suspend fun generateGradleMetadata(
        checksumPublishables: Map<String, List<MavenPublishable>>,
        coordsPerPlatform: Map<Platform, MavenCoordinates>,
        modulePublishablesFromOtherTasks: List<MavenPublishable>,
        overrides: PublicationCoordinatesOverrides,
    ): List<MavenPublishable> = buildList {
        addAll(
            generateGradleMetadataForLeafPlatforms(
                checksumPublishables, coordsPerPlatform, modulePublishablesFromOtherTasks, overrides
            )
        )
        generateCommonGradleMetadata(checksumPublishables, coordsPerPlatform, modulePublishablesFromOtherTasks)
            ?.also { add(it) }
    }

    suspend fun generateGradleMetadataForLeafPlatforms(
        checksumPublishables: Map<String, List<MavenPublishable>>,
        coordsPerPlatform: Map<Platform, MavenCoordinates>,
        modulePublishablesFromOtherTasks: List<MavenPublishable>,
        overrides: PublicationCoordinatesOverrides,
    ): List<MavenPublishable> =
        coordsPerPlatform.filterNot { it.key == Platform.COMMON }
            .entries
            .mapConcurrently { [platform, coords] ->
                // Modules without sources don't produce any main artifact for the platform (no klib, for instance),
                // and that's OK: the publication then only contains the POM, the metadata, and the sources jar.
                val platformSpecificArtifact = modulePublishablesFromOtherTasks.singleWithCoordinatesOrNull(coords)
                val platformSpecificCinteropArtifacts = modulePublishablesFromOtherTasks.filter {
                    it.coordinates.isCinteropClassifier && it.coordinates.copy(classifier = null) == coords
                }

                val sourcesJar = findSourcesArtifactFor(coords, modulePublishablesFromOtherTasks)
                generateGradleMetadataForLeafPlatform(
                    module,
                    platform,
                    taskOutputRoot.path,
                    checksumPublishables,
                    platformSpecificArtifact,
                    platformSpecificCinteropArtifacts,
                    sourcesJar,
                    overrides
                ).toMavenPublishable(coords)
            }

    private suspend fun generateCommonGradleMetadata(
        checksumPublishables: Map<String, List<MavenPublishable>>,
        coordsPerPlatform: Map<Platform, MavenCoordinates>,
        modulePublishablesFromOtherTasks: List<MavenPublishable>,
    ): MavenPublishable? {
        if (!module.isMultiplatformPublication()) return null

        val commonCoordinates = module.publicationCoordinates(Platform.COMMON)

        val allMetadataArtifact = modulePublishablesFromOtherTasks.singleWithCoordinatesOrNull(commonCoordinates)
        val allMetadataSourcesArtifact = findSourcesArtifactFor(commonCoordinates, modulePublishablesFromOtherTasks)

        val allMetadataGradleModuleFile = generateCommonGradleModuleMetadata(
            module, taskOutputRoot.path,
            allMetadataJarPath = allMetadataArtifact?.path,
            allMetadataSourcesJarPath = allMetadataSourcesArtifact?.path,
            checksumPublishables
        )
        val allMetadataGradleModulePublishable =
            allMetadataGradleModuleFile.toMavenPublishable(coordsPerPlatform[Platform.COMMON]!!)
        return allMetadataGradleModulePublishable
    }

    private fun findSourcesArtifactFor(
        coords: MavenCoordinates,
        modulePublishablesFromOtherTasks: List<MavenPublishable>,
    ): MavenPublishable? = if (module.publishingSettings.publishSources) {
        val sourceCoordinates = coords.copy(classifier = "sources")
        modulePublishablesFromOtherTasks.singleWithCoordinatesOrNull(sourceCoordinates)
    } else null

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
        hasMainArtifact: Boolean,
    ): Path {
        val artifactId = module.publishingSettings.artifactId ?: module.userReadableName
        val tempPath = taskOutputRoot.path.resolve("$artifactId-${platform.pretty}.pom")
        tempPath.writePomFor(module, platform, overrides, gradleMetadataComment = true, hasMainArtifact = hasMainArtifact)
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
        val signatureFileName = artifact.coordinates.mavenFileName(artifact.mavenArtifactExtension) + ".asc"
        val signatureFilePath = taskOutputRoot.path.resolve("signatures/$signatureFileName").createParentDirectories()
        try {
            // TODO add some fine-grained progress reporting instead (see AMPER-5487)
            logger.info("Signing artifact '${artifact.path.name}'…")
            sign(artifact.path, outputSignatureFile = signatureFilePath)
        } catch (e: PgpSigningKeyPassphraseException) {
            if (e.passphrasePresent) {
                userReadableError("Incorrect PGP signing key passphrase, please check the KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE environment variable", e)
            } else {
                userReadableError("The key provided in the KOTLIN_TOOLCHAIN_SIGNING_KEY environment variable requires a passphrase, but KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE was not set", e)
            }
        } catch (e: PgpSigningException) {
            userReadableError("PGP signing failed for artifact '${artifact.path.name}': ${e.message}", e)
        }
        return MavenPublishable(
            coordinates = artifact.coordinates,
            mavenArtifactExtension = artifact.mavenArtifactExtension + ".asc",
            path = signatureFilePath,
        )
    }

    private suspend fun generateChecksums(
        publishable: MavenPublishable,
        checksumsToPublish: List<Checksum>,
    ): List<MavenPublishable> = checksumsToPublish.mapConcurrently { algorithm ->
        generateChecksum(publishable, algorithm)
    }

    private suspend fun generateChecksum(publishable: MavenPublishable, algorithm: Checksum): MavenPublishable =
        withContext(Dispatchers.IO) {
            val algorithmExtension = algorithm.mavenArtifactExtensionSuffix
            val checksum = publishable.path.readBytes().hash(algorithm.algorithmName).toHexString()
            val checksumFileName = publishable.coordinates.mavenFileName(publishable.mavenArtifactExtension)
            val checksumFile = taskOutputRoot.path.resolve("checksums/$checksumFileName.$algorithmExtension")
            checksumFile.createParentDirectories().writeText(checksum)
            publishable.copy(
                mavenArtifactExtension = "${publishable.mavenArtifactExtension}.$algorithmExtension",
                path = checksumFile,
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
) {
    /**
     * Whether this publishable is a signature file. This matters because we don't want to generate checksums for those.
     */
    val isSignature: Boolean
        get() = mavenArtifactExtension.endsWith("asc")

    val isChecksum: Boolean
        get() = Checksum.entries.any { mavenArtifactExtension.endsWith(it.mavenArtifactExtensionSuffix) }
}

private val Checksum.mavenArtifactExtensionSuffix get() = algorithmName.lowercase().replace("-", "")

/**
 * The name this artifact must have in the Maven repository layout: `<artifactId>-<version>[-<classifier>].<extension>`.
 *
 * Files derived from an artifact (checksums, signatures) must be named after this, and *not* after the name of the
 * file they are derived from: the same file name can be produced for several platforms (all klibs are named
 * `<moduleName>.klib`, for instance), so deriving from it makes the outputs of different platforms overwrite each
 * other, and each artifact then gets published with some other platform's checksum or signature.
 */
internal fun MavenCoordinates.mavenFileName(extension: String): String {
    val nonNullVersion = version ?: error("Missing 'version' in Maven coordinates: ${toPrettyString()}")
    val classifierSuffix = classifier?.let { "-$it" } ?: ""
    return "$artifactId-$nonNullVersion$classifierSuffix.$extension"
}

/**
 * Returns the publishable with the given [coordinates], or null if no task produced such an artifact.
 *
 * A missing artifact is not an error: modules without sources are valid (only the `module.yaml` file is required),
 * and they don't produce any artifact for some platforms (a klib for native platforms, for instance).
 */
private fun List<MavenPublishable>.singleWithCoordinatesOrNull(coordinates: MavenCoordinates): MavenPublishable? {
    val matches = filter { it.coordinates == coordinates }
    if (matches.size > 1) {
        error("Multiple publishable artifacts with the same coordinates $coordinates:\n" +
                matches.joinToString("\n") { " - ${it.path}" })
    }
    return matches.singleOrNull()
}

private fun TaskResult.toMavenPublishables(coordsPerPlatform: Map<Platform, MavenCoordinates>) = when (this) {
    is JvmClassesJarTask.Result -> listOf(toMavenPublishable(coordsPerPlatform))
    is SourcesJarTask.Result -> listOf(toMavenPublishable(coordsPerPlatform))
    is JavadocJarTask.Result -> listOf(toMavenPublishable(coordsPerPlatform))
    is NativeCompileKlibTask.Result -> toMavenPublishables(coordsPerPlatform)
    is NativeCInteropGenerateKlibTask.Result -> toMavenPublishables(coordsPerPlatform)
    is WebCompileKlibTask.Result -> toMavenPublishables(coordsPerPlatform)
    is ResolveExternalDependenciesTask.Result -> emptyList() // this is just for coords overrides, not extra artifacts
    is AssembleAllMetadataTask.Result -> toMavenPublishables(coordsPerPlatform)
    is AndroidAarTask.Result -> toMavenPublishables(coordsPerPlatform)
    is EmptyTaskResult -> emptyList() // task ia noop and has not produced a result
    else -> error("Unsupported dependency result: ${javaClass.name}")
}

private fun JvmClassesJarTask.Result.toMavenPublishable(coordsPerPlatform: Map<Platform, MavenCoordinates>): MavenPublishable =
    jarPath.toMavenPublishable(coordsPerPlatform.getValue(platform))

private fun SourcesJarTask.Result.toMavenPublishable(coordsPerPlatform: Map<Platform, MavenCoordinates>): MavenPublishable =
    jarPath.toMavenPublishable(coordsPerPlatform.getValue(platform).copy(classifier = "sources"))

private fun JavadocJarTask.Result.toMavenPublishable(coordsPerPlatform: Map<Platform, MavenCoordinates>): MavenPublishable =
    jarPath.toMavenPublishable(coordsPerPlatform.getValue(platform).copy(classifier = "javadoc"))

private fun NativeCompileKlibTask.Result.toMavenPublishables(
    coordsPerPlatform: Map<Platform, MavenCoordinates>
): List<MavenPublishable> = listOfNotNull(compiledKlib?.toMavenPublishable(coordsPerPlatform.getValue(platform)))

private fun NativeCInteropGenerateKlibTask.Result.toMavenPublishables(
    coordsPerPlatform: Map<Platform, MavenCoordinates>
): List<MavenPublishable> = path.listDirectoryEntries()
    .filter { it.extension == "klib" }
    // sorted, so that the order of cinterop artifacts in the Gradle module metadata is reproducible
    .sorted()
    .map {
        val cinteropKLibCoordinates = coordsPerPlatform.getValue(platform).copy(classifier = it.cinteropClassifier())
        it.toMavenPublishable(cinteropKLibCoordinates)
    }

private fun WebCompileKlibTask.Result.toMavenPublishables(
    coordsPerPlatform: Map<Platform, MavenCoordinates>
): List<MavenPublishable> = listOfNotNull(compiledKlib?.toMavenPublishable(coordsPerPlatform.getValue(platform)))

private fun AssembleAllMetadataTask.Result.toMavenPublishables(
    coordsPerPlatform: Map<Platform, MavenCoordinates>
): List<MavenPublishable> = listOfNotNull(
    allMetadataJarPath?.toMavenPublishable(coordsPerPlatform.getValue(Platform.COMMON)),
    allMetadataSourcesJarPath?.toMavenPublishable(coordsPerPlatform.getValue(Platform.COMMON).copy(classifier = "sources")  ),
)

private fun AndroidAarTask.Result.toMavenPublishables(
    coordsPerPlatform: Map<Platform, MavenCoordinates>
): List<MavenPublishable> = [ aarPath.toMavenPublishable(coordsPerPlatform.getValue(Platform.ANDROID)) ]

private fun Path.toMavenPublishable(
    coords: MavenCoordinates,
    extension: String = this.extension,
): MavenPublishable = MavenPublishable(
    coordinates = coords,
    mavenArtifactExtension = extension,
    path = this,
)