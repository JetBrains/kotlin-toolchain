/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.publication

import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.crypto.pgp.AsciiArmoredPgpKey
import org.jetbrains.amper.crypto.pgp.PgpKeyParsingException
import org.jetbrains.amper.crypto.pgp.PgpSigner
import org.jetbrains.amper.crypto.pgp.PgpSigningException
import org.jetbrains.amper.crypto.pgp.PgpSigningKeyPassphraseException
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.isArtifactSigningEnabled
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.incrementalcache.executeForSerializable
import org.jetbrains.amper.incrementalcache.getDynamicInputs
import org.jetbrains.amper.tasks.MavenPublishable
import org.jetbrains.amper.tasks.MavenPublishablesResult
import org.jetbrains.amper.tasks.PrepareMavenPublishablesTask
import org.jetbrains.amper.tasks.TaskOutputRoot
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.mavenFileName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.name

/**
 * Signs the publishables prepared by [PrepareMavenPublishablesTask], and returns them along with their signatures.
 * If artifact signing is disabled for the [module], the publishables are returned as-is.
 *
 * This is a separate task so that publications that don't need signatures (to maven local, for instance) don't
 * require a signing key.
 */
class SignMavenPublishablesTask(
    override val taskName: TaskName,
    private val module: AmperModule,
    private val taskOutputRoot: TaskOutputRoot,
    private val incrementalCache: IncrementalCache,
) : Task {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val publishables = dependenciesResult.filterIsInstance<PrepareMavenPublishablesTask.Result>()
            .singleOrNull()
            ?.publishables
            ?: error("Expected single PrepareMavenPublishablesTask.Result from task dependencies")

        if (!module.isArtifactSigningEnabled()) {
            return Result(publishables)
        }

        // Checksums are not mandatory for the signature files themselves, and we want to limit the number
        // of published files, so we don't generate checksums for the signatures here.
        val publishablesToSign = publishables.filterNot { it.isChecksum }

        val signatures = incrementalCache.executeForSerializable<List<MavenPublishable>>(
            key = taskName.id.value,
            inputValues = mapOf("publishablesToSign" to Json.encodeToString(publishablesToSign)),
            inputFiles = publishablesToSign.map { it.path },
        ) {
            taskOutputRoot.path.deleteRecursively()
            taskOutputRoot.path.createDirectories()

            val artifactSigner = createSignerFromEnvConfig()
            publishablesToSign.map { artifactSigner.signArtifact(it) }
        }

        return Result(publishables + signatures)
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
        val signatureFilePath = taskOutputRoot.path.resolve(signatureFileName).createParentDirectories()
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

    class Result(override val publishables: List<MavenPublishable>) : MavenPublishablesResult
}
