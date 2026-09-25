/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package maven

import Repository
import org.apache.maven.model.Model
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.dependency.resolution.LocalM2RepositoryFinder
import org.jetbrains.amper.frontend.schema.Checksum
import org.jetbrains.amper.maven.publish.createPlexusContainer
import org.jetbrains.amper.maven.publish.deployToRemoteRepo
import org.jetbrains.amper.maven.publish.installToMavenLocal
import org.jetbrains.amper.maven.publish.writePom
import java.nio.file.Path
import kotlin.io.path.extension

private const val KotlinGroupId = "org.jetbrains.kotlin"

internal val JetBrainsTeamAmperRepository by lazy {
    val username = System.getenv("JETBRAINS_TEAM_AMPER_USERNAME")
        ?: error("JETBRAINS_TEAM_AMPER_USERNAME environment variable is not set")
    val password = System.getenv("JETBRAINS_TEAM_AMPER_PASSWORD")
        ?: error("JETBRAINS_TEAM_AMPER_PASSWORD environment variable is not set")
    val builder = RemoteRepository.Builder(
        "jetbrains-team-amper",
        "default",
        "https://packages.jetbrains.team/maven/p/amper/amper",
    )
    val authBuilder = AuthenticationBuilder()
    authBuilder.addUsername(username)
    authBuilder.addPassword(password)
    builder.setAuthentication(authBuilder.build())
    builder.build()
}

internal fun kotlinToolchainArtifact(
    artifactId: String,
    file: Path,
    version: String = AmperBuild.mavenVersion,
    classifier: String? = null,
    extension: String = file.extension,
): Artifact = DefaultArtifact(
    KotlinGroupId,
    artifactId,
    classifier,
    extension,
    version,
).setFile(file.toFile())

internal fun createSimplePom(targetDir: Path, artifactId: String, version: String = AmperBuild.mavenVersion): Path {
    val model = Model()
    model.modelVersion = "4.0.0"
    model.name = artifactId
    model.groupId = KotlinGroupId
    model.artifactId = artifactId
    model.version = version
    return targetDir.resolve("$artifactId.pom").apply { writePom(model) }
}

internal fun Repository.publish(artifacts: List<Artifact>) {
    val localMavenRepoPath = LocalM2RepositoryFinder.findPath()
    val plexusContainer = createPlexusContainer(Distribution::class.java.classLoader)
    when (this) {
        Repository.MavenLocal -> plexusContainer.installToMavenLocal(
            localRepositoryPath = localMavenRepoPath,
            artifacts = artifacts,
        )
        Repository.JetBrainsTeamAmperMaven -> plexusContainer.deployToRemoteRepo(
            remoteRepository = JetBrainsTeamAmperRepository,
            localRepositoryPath = localMavenRepoPath,
            artifacts = artifacts,
            checksumAlgorithms = Checksum.entries,
        )
    }
}
