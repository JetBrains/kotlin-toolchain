/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.publication

import com.github.ajalt.mordant.terminal.Terminal
import org.codehaus.plexus.PlexusContainer
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.jetbrains.amper.cli.terminal.printSuccessfulPublicationToMavenLocal
import org.jetbrains.amper.cli.terminal.printSuccessfulPublicationToRemoteMaven
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.dependency.resolution.MavenLocalRepository
import org.jetbrains.amper.engine.PublishTask
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.RepositoryModel
import org.jetbrains.amper.frontend.aomBuilder.readCredentials
import org.jetbrains.amper.frontend.isMavenLocal
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.schema.Checksum
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.maven.publish.createPlexusContainer
import org.jetbrains.amper.maven.publish.deployToRemoteRepo
import org.jetbrains.amper.maven.publish.installToMavenLocal
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.tasks.MavenPublishable
import org.jetbrains.amper.tasks.PrepareMavenPublishablesTask
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.jetbrains.amper.telemetry.useWithoutCoroutines
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.pathString

private val mavenLocalRepository by lazy {
    spanBuilder("Initialize maven local repository").useWithoutCoroutines {
        MavenLocalRepository.Default
    }
}

/**
 * A comparator to sort artifacts for publication, just to have some reproducible order.
 */
private val artifactComparator = compareBy<Artifact>(
    { it.groupId },
    { it.artifactId },
    { it.classifier },
    { it.extension },
)

class MavenPublishTask(
    override val taskName: TaskName,
    override val module: AmperModule,
    val targetRepository: RepositoryModel.Publish,
    private val incrementalCache: IncrementalCache,
    private val terminal: Terminal,
) : PublishTask {

    override val targetRepositoryId: String
        get() = targetRepository.id

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        val localRepositoryPath = mavenLocalRepository.repository
        val artifacts = dependenciesResult.filterIsInstance<PrepareMavenPublishablesTask.Result>()
            .flatMap { it.publishables }
            // todo (AB) : [AMPER-719] Check if we could disable checksums generation in Eclise Aether library
            //  and reuse what is already calculated by PrepareMavenPublishablesTask.
            .filterNot { it.isChecksum } // the Eclise Aether library generates checksums on its own
            .map { it.toMavenArtifact() }
            // We sort for reproducibility because the artifact order matters for the Eclipse Aether library
            // (it determines the order in maven-metadata.xml, at least when using SNAPSHOTs)
            .sortedWith(artifactComparator)

        /**
         * Publish uses a different code to publish to maven local and to any other remote
         * repositories.
         * Maven local is different, since there are some settings to set it up
         * (https://maven.apache.org/resolver/local-repository.html) and also it does not use checksums
         * https://maven.apache.org/plugins/maven-install-plugin/index.html#important-note-for-version-3-0-0
         * > The `install:install` goal does not support creating checksums anymore via -DcreateChecksum=true cause this
         * > option has been removed. Details can be found in MINSTALL-143.
         */
        spanBuilder("Maven publish").use {
            context(getPlexusContainer(executionContext)) {
                if (targetRepository.isMavenLocal) {
                    installToMavenLocal(artifacts, localRepositoryPath)
                } else {
                    publishToRemoteRepo(artifacts, localRepositoryPath, module.publishingSettings.checksums)
                }
            }
        }

        return Result()
    }

    context(plexusContainer: PlexusContainer)
    private suspend fun installToMavenLocal(
        artifacts: List<Artifact>,
        localRepositoryPath: Path,
    ) {
        try {
            incrementalCache.execute(
                key = taskName.id.value,
                inputValues = mapOf(
                    "localM2Path" to localRepositoryPath.pathString,
                    "artifacts" to artifacts.joinToString(",") { it.toString() },
                ),
                inputFiles = artifacts.map { it.file.toPath() },
            ) {
                logger.infoNoConsole("Installing artifacts of module '${module.userReadableName}' to local maven repository at $localRepositoryPath")
                val installedPaths = plexusContainer.installToMavenLocal(localRepositoryPath, artifacts)
                terminal.printSuccessfulPublicationToMavenLocal(module)
                IncrementalCache.ExecutionResult(outputFiles = installedPaths)
            }
        } catch (e: Exception) {
            userReadableError("Couldn't install artifacts of module '${module.userReadableName}' to maven local: $e", e)
        }
    }

    context(plexusContainer: PlexusContainer, _: ProblemReporter)
    private fun publishToRemoteRepo(
        artifacts: List<Artifact>,
        localRepositoryPath: Path,
        checksums: List<Checksum>,
    ) {
        val remoteRepository = targetRepository.toMavenRemoteRepository()

        logger.infoNoConsole(
            "Publishing artifacts of module '${module.userReadableName}' to " +
                    "remote maven repository at ${remoteRepository.url} (id: '${remoteRepository.id}')"
        )
        try {
            plexusContainer.deployToRemoteRepo(remoteRepository, localRepositoryPath, artifacts, checksums)
            terminal.printSuccessfulPublicationToRemoteMaven(module, targetRepository)
        } catch (e: Exception) {
            userReadableError(
                "Couldn't publish artifacts of module '${module.userReadableName}' to repository '${remoteRepository.id}': $e",
                e
            )
        }
    }

    private fun PrepareMavenPublishablesTask.Result.mavenArtifacts(): List<Artifact> =
        publishables.map { it.toMavenArtifact() }

    private fun MavenPublishable.toMavenArtifact(): Artifact = DefaultArtifact(
        coordinates.groupId,
        coordinates.artifactId,
        coordinates.classifier,
        mavenArtifactExtension,
        coordinates.version,
    ).setFile(path.toFile())

    context(_: ProblemReporter)
    private fun RepositoryModel.Publish.toMavenRemoteRepository(): RemoteRepository {
        val builder = RemoteRepository.Builder(id, "default", url)
        val credentials = when (this) {
            is RepositoryModel.ResolveAndPublish -> credentials  // Resolved eagerly
            is RepositoryModel.PublishOnly -> credentialsSource?.let {
                it.readCredentials() ?: userReadableError("Unable to get credentials for publishing, see errors above")
            }
        }

        if (credentials != null) {
            val authBuilder = AuthenticationBuilder()
            authBuilder.addUsername(credentials.userName)
            authBuilder.addPassword(credentials.password)
            builder.setAuthentication(authBuilder.build())
        }
        return builder.build()
    }

    class Result : TaskResult

    companion object {
        private val plexusContainerCache = ConcurrentHashMap<String, PlexusContainer>()

        suspend fun getPlexusContainer(executionContext: TaskGraphExecutionContext): PlexusContainer =
            plexusContainerCache.getOrPut(executionContext.executionId) {
                createPlexusContainer().also { container ->
                    executionContext.addPostGraphExecutionHook {
                        spanBuilder("Dispose of Maven's PlexusContainer").use {
                            container.dispose()
                        }
                    }
                }
            }
    }

    private val logger = LoggerFactory.getLogger(javaClass)
}
