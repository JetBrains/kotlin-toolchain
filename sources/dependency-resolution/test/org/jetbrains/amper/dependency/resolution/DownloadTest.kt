/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics.HashesMismatch
import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics.SuccessfulDownload
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.SimpleMessage
import org.jetbrains.amper.dependency.resolution.diagnostics.UnableToDownloadFile
import org.jetbrains.amper.dependency.resolution.diagnostics.UnableToResolveDependency
import org.jetbrains.amper.test.dr.toMavenCoordinates
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * todo (AB) : What if downloaded checksum file is corrupted?
 *  would that lead to stable hash verification issue that is not resolvable automatically?
 */
class DownloadTest: BaseDRTest() {

    override val testDataPath: Path = super.testDataPath / "download" / "goldenFiles"

    @TempDir
    lateinit var tmpDir: Path

    private fun uniqueCacheRoot() = (tmpDir / UUID.randomUUID().toString().substring(0, 8)).createDirectories()

    /**
     * This test checks that an artifact is successfully re-downloaded after the first attempt failed due
     * to hashes mismatch.
     */
    @Test
    fun `redownloading on hash mismatch succeed after it failed one`(testInfo: TestInfo) = runSlowDrTest {
        // Call resolve dependencies, configuring [TestHttpClient] to return incorrect content instead of actual
        // artifact for the first time only. All later requests download the correct artifact.
        // This way, the first attempt to download fails with the 'hash mismatch' error.
        // The second attempt is successful, and the entire artifact downloading succeeds.
        val resolvedGraph = resolveDependencyHashMismatchAttempts(1)
        resolvedGraph.verifyMessages()

        // Check that there is a suppressed diagnostic about the first (failed) download attempt
        val suppressedHashesMismatchDiagnostics = resolvedGraph.children.single().messages
            .filterIsInstance<SimpleMessage>()
            .single { it.id == SuccessfulDownload.id && it.childMessages.isNotEmpty() }
            .childMessages
            .filter { it.severity == Severity.ERROR && it.id == HashesMismatch.id }
        assertEquals(1, suppressedHashesMismatchDiagnostics.size,
            "The first attempt to download the artifact should have failed due to hashes mismatch")

        assertFiles(testInfo, resolvedGraph)
    }

    /**
     * This test checks that ann entire artifact downloading fails after 3 consecutive attempts to download it failed due
     * to hashes mismatch.
     */
    @Test
    fun `redownloading on hash mismatch fails after 3 failed attempts`(testInfo: TestInfo) = runSlowDrTest {
        // Call resolve dependencies, configuring [TestHttpClient] to return incorrect content instead of actual
        // artifact 3 times in a row.
        // This way, each of those 3 times hash verification fails.
        // But the artifact is tried to be redownloaded no more than 3 times
        // in case checksum doesn't match (the number of attempts is hardcoded in [download]).
        // Hence, the entire downloading of the artifact fails with the 'hash mismatch' error.
        val unresolvedGraph = resolveDependencyHashMismatchAttempts(3)
        assertTheOnlyNonInfoMessage<UnableToResolveDependency>(unresolvedGraph, Severity.ERROR)

        val failedDownloadDiagnostics = unresolvedGraph.children.single().messages
            .filterIsInstance<UnableToResolveDependency>().single().childMessages
            .filterIsInstance<UnableToDownloadFile>().single().childMessages
            .filterIsInstance<SimpleMessage>().filter { it.id == HashesMismatch.id }
        assertEquals(3, failedDownloadDiagnostics.size,
            "3 attempts to download the artifact should have failed due to hashes mismatch")

        assertFiles(testInfo, unresolvedGraph)
    }

    private suspend fun resolveDependencyHashMismatchAttempts(hashMismatchResponsesCount: Int): DependencyNode {
        val localStorage = uniqueCacheRoot()

        val coordinates = "org.jetbrains:annotations:13.0".toMavenCoordinates()
        val repository = REDIRECTOR_MAVEN_CENTRAL

        val context = Context {
            this.repositories = listOf(repository)
            this.cache = getDefaultFileCacheBuilder(localStorage).let {
                {
                    it()
                    readOnlyExternalRepositories = emptyList()
                }
            }
        }

        context.resolutionCache[httpClientKey] = TestHttpClient.create(
            overriddenUrl = mapOf(
                // substitute request of pom downloading with request of pom.sha1 downloading,
                // to cause hash mismatch error
                coordinates.toUrl(repository, "pom") to TestHttpClient.OverriddenUrl(
                    coordinates.toUrl(repository, "pom.sha1"),
                    hashMismatchResponsesCount
                ),
            )
        )

        val root = RootDependencyNodeWithContext(
            templateContext = context,
            children = listOf(
                context.toMavenDependencyNode(
                    "org.jetbrains:annotations:13.0".toMavenCoordinates(),
                    false
                )
            )
        )
        val resolvedGraph = Resolver().resolveDependencies(root).root
        return resolvedGraph
    }
}