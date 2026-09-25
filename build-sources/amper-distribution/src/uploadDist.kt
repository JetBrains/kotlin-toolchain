/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import maven.createSimplePom
import maven.kotlinToolchainArtifact
import maven.publish
import org.eclipse.aether.artifact.Artifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.stdlib.io.path.withTempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

private const val KotlinCliArtifactId = "kotlin-cli"

private const val KotlinCliSdkmanArtifactId = "kotlin-cli-sdkman"

@TaskAction
fun uploadDist(
    @Input distribution: Distribution,
    @Input sdkmanArchive: Path,
    repository: Repository,
) {
    withTempDir { tempDir ->
        val artifacts = context(tempDir) {
            distArtifacts(distribution, sdkmanArchive)
        }
        repository.publish(artifacts)
    }
}

context(tempDirectory: Path)
fun distArtifacts(distribution: Distribution, sdkmanArchive: Path): List<Artifact> =
    distribution.artifacts(KotlinCliArtifactId) + sdkmanArtifacts(KotlinCliSdkmanArtifactId, sdkmanArchive)

context(tempDirectory: Path)
private fun Distribution.artifacts(artifactId: String): List<Artifact> {
    val wrapperArtifacts = wrappersDir.listDirectoryEntries()
        .map { kotlinToolchainArtifact(artifactId, file = it, classifier = "wrapper") }
    val installerArtifacts = installersDir.listDirectoryEntries()
        .map { kotlinToolchainArtifact(artifactId, file = it, classifier = "installer") }
    val tarGzDistArtifact = kotlinToolchainArtifact(artifactId, file = cliTgz, classifier = "dist")
    // we also generate a POM file to please maven and ensure maven-metadata.xml is properly updated
    val pomArtifact = kotlinToolchainArtifact(artifactId, file = createSimplePom(tempDirectory, artifactId))
    return buildList {
        addAll(wrapperArtifacts)
        addAll(installerArtifacts)
        add(tarGzDistArtifact)
        add(pomArtifact)
    }
}

context(tempDirectory: Path)
private fun sdkmanArtifacts(artifactId: String, sdkmanArchive: Path): List<Artifact> {
    val sdkmanArchiveArtifact = kotlinToolchainArtifact(artifactId, file = sdkmanArchive)
    val pomFile = createSimplePom(tempDirectory, artifactId)
    val pomArtifact = kotlinToolchainArtifact(artifactId, file = pomFile)
    return listOf(sdkmanArchiveArtifact, pomArtifact)
}
