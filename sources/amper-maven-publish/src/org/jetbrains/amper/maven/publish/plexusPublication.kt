/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.maven.publish

import org.apache.maven.bridge.MavenRepositorySystem
import org.apache.maven.execution.DefaultMavenExecutionRequest
import org.apache.maven.execution.MavenExecutionRequest
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory
import org.apache.maven.settings.Mirror
import org.codehaus.plexus.DefaultContainerConfiguration
import org.codehaus.plexus.DefaultPlexusContainer
import org.codehaus.plexus.PlexusConstants
import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.classworlds.ClassWorld
import org.codehaus.plexus.logging.AbstractLogger
import org.codehaus.plexus.logging.BaseLoggerManager
import org.codehaus.plexus.logging.Logger
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.deployment.DeployRequest
import org.eclipse.aether.installation.InstallRequest
import org.eclipse.aether.repository.RemoteRepository
import org.jetbrains.amper.frontend.schema.Checksum
import org.jetbrains.amper.mavencentral.MavenCentralDefaultConfiguration
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Creates a new [PlexusContainer] initialized from the current context classloader.
 */
fun createPlexusContainer(
    classLoader: ClassLoader = Thread.currentThread().getContextClassLoader(),
): PlexusContainer {
    val containerConfiguration = DefaultContainerConfiguration()
        .setClassWorld(ClassWorld("plexus.core", classLoader))
        .setName("mavenCore")
        .setClassPathScanning(PlexusConstants.SCANNING_INDEX)
        .setAutoWiring(true)

    return DefaultPlexusContainer(containerConfiguration).also { it.loggerManager = Slf4jLoggerManager }
}

private object Slf4jLoggerManager : BaseLoggerManager() {
    init {
        // Maven publication logs a TON of things at debug level, which is more like a trace level.
        // It produces 900MB of debug logs for Amper's own publication. We don't want that even in debug.log.
        threshold = Logger.LEVEL_INFO
    }

    override fun createLogger(name: String): Logger = object : AbstractLogger(LEVEL_INFO, name) {
        private val logger = LoggerFactory.getLogger(name)

        override fun debug(message: String, throwable: Throwable?) = logger.debug(message, throwable)
        // The 'info' level is mapped to 'debug' because it's too low level, and we don't want this in the console
        override fun info(message: String, throwable: Throwable?) = logger.debug(message, throwable)
        override fun warn(message: String, throwable: Throwable?) = logger.warn(message, throwable)
        override fun error(message: String, throwable: Throwable?) = logger.error(message, throwable)
        override fun fatalError(message: String, throwable: Throwable?) = logger.error(message, throwable)
        override fun getChildLogger(name: String): Logger = this
    }
}

fun PlexusContainer.deployToRemoteRepo(
    remoteRepository: RemoteRepository,
    localRepositoryPath: Path,
    artifacts: List<Artifact>,
    checksumAlgorithms: List<Checksum>,
) {
    val deployRequest = DeployRequest()
    deployRequest.repository = remoteRepository

    for (artifact in artifacts) {
        deployRequest.addArtifact(artifact)
    }

    val repositorySession = createRepositorySession(localRepositoryPath, checksumAlgorithms)
    repositorySystem.deploy(repositorySession, deployRequest)
}

/**
 * Installs the given [artifacts] to the local maven repository located at [localRepositoryPath].
 *
 * @return the paths of the installed artifacts inside the local repository.
 */
@IgnorableReturnValue
fun PlexusContainer.installToMavenLocal(localRepositoryPath: Path, artifacts: List<Artifact>): List<Path> {
    val installRequest = InstallRequest()
    for (artifact in artifacts) {
        installRequest.addArtifact(artifact)
    }
    val repositorySession = createRepositorySession(localRepositoryPath)
    val result = repositorySystem.install(repositorySession, installRequest)
    // The artifacts in the result still point to the original input files, so we resolve their actual location inside
    // the local repository using the layout defined by the local repository manager.
    val localRepositoryManager = repositorySession.localRepositoryManager
    return result.artifacts.map { artifact ->
        localRepositoryPath.resolve(localRepositoryManager.getPathForLocalArtifact(artifact))
    }
}

fun PlexusContainer.createRepositorySession(
    localRepositoryPath: Path,
    checksumAlgorithms: List<Checksum> = [Checksum.MD5, Checksum.SHA1],
): RepositorySystemSession {
    val request = mavenRepositorySystem.createMavenExecutionRequest(localRepositoryPath)
    val session = repositorySystemSessionFactory.newRepositorySession(request)
    // We let the user choose which checksums to publish
    // Note: in more recent versions of the resolver, we could customize aether.checksums.uploadChecksumAlgorithms
    // independently. We're using an old one right now.
    session.setConfigProperty("aether.checksums.algorithms", checksumAlgorithms.joinToString(",") { it.algorithmName })

    // Disable caching HTTP connection pooling between sessions, to allow closing the connection pool later.
    // If we don't do this, the connection manager is stored in a GlobalState and the LocalState doesn't delegate close()
    session.setConfigProperty("aether.connector.http.cacheState", false)
    // Gateway timeouts should also be retried, we faced this a bunch of times (see AMPER-5476)
    session.setConfigProperty(
        ConfigurationProperties.HTTP_RETRY_HANDLER_SERVICE_UNAVAILABLE,
        ConfigurationProperties.DEFAULT_HTTP_RETRY_HANDLER_SERVICE_UNAVAILABLE + ",504",
    )
    return session
}

fun MavenRepositorySystem.createMavenExecutionRequest(localRepositoryPath: Path): MavenExecutionRequest {
    val request = createDefaultMavenExecutionRequest()

    request.localRepository = createLocalRepository(request, localRepositoryPath.toFile())
    request.systemProperties = System.getProperties()

    return request
}

private fun createDefaultMavenExecutionRequest(): DefaultMavenExecutionRequest {
    val request = DefaultMavenExecutionRequest()
    if (!MavenCentralDefaultConfiguration.isDefaultUrl) {
        request.addMirror(
            Mirror().also {
                it.id = "central-mirror"
                it.name = "Maven Central Mirror"
                it.url = MavenCentralDefaultConfiguration.url
                it.mirrorOf = "central"
            }
        )
    }
    return request
}

private val PlexusContainer.repositorySystem: RepositorySystem
    get() = lookup(RepositorySystem::class.java)

val PlexusContainer.mavenRepositorySystem: MavenRepositorySystem
    get() = lookup(MavenRepositorySystem::class.java)

private val PlexusContainer.repositorySystemSessionFactory: DefaultRepositorySystemSessionFactory
    get() = lookup(DefaultRepositorySystemSessionFactory::class.java)
