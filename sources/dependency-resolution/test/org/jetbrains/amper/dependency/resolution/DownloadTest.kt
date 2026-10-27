/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import com.sun.net.httpserver.HttpServer
import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics.HashesMismatch
import org.jetbrains.amper.dependency.resolution.diagnostics.DependencyResolutionDiagnostics.SuccessfulDownload
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.SimpleMessage
import org.jetbrains.amper.dependency.resolution.diagnostics.UnableToDownloadFile
import org.jetbrains.amper.dependency.resolution.diagnostics.UnableToResolveDependency
import org.jetbrains.amper.test.dr.toMavenCoordinates
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
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

    /**
     * Some proxies answer 404 to a HEAD request for a file they haven't cached yet, while a GET request makes them
     * fetch and serve it. Such a repository must not be taken for one that doesn't have the artifact.
     */
    @Test
    fun `artifact is resolved from a repository that answers 404 to HEAD requests`() = runDrTest {
        val pom = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>lib</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
            </project>
        """.trimIndent().toByteArray()
        val files = mapOf(
            "/org/example/lib/1.0/lib-1.0.pom" to pom,
            "/org/example/lib/1.0/lib-1.0.pom.sha1" to pom.sha1().toByteArray(),
        )
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val content = files[exchange.requestURI.path]
            when {
                exchange.requestMethod == "HEAD" || content == null -> exchange.sendResponseHeaders(404, -1)
                else -> {
                    // A proxy fetching the file from upstream answers slower than it answers a HEAD request
                    Thread.sleep(300)
                    exchange.sendResponseHeaders(200, content.size.toLong())
                    exchange.responseBody.write(content)
                }
            }
            exchange.close()
        }
        server.start()
        try {
            val graph = resolve("org.example:lib:1.0", MavenRepository("http://127.0.0.1:${server.address.port}"))
            val errors = graph.distinctBfsSequence()
                .flatMap { node -> node.messages.filter { it.severity >= Severity.ERROR } }
                .map { it.message }
                .toList()
            assertEquals([], errors)
        } finally {
            server.stop(0)
        }
    }

    private fun ByteArray.sha1(): String =
        MessageDigest.getInstance("SHA-1").digest(this).joinToString("") { "%02x".format(it) }

    private suspend fun resolve(coordinates: String, repository: MavenRepository): DependencyNode {
        val context = Context {
            this.repositories = listOf(repository)
            this.cache = getDefaultFileCacheBuilder(uniqueCacheRoot()).let {
                {
                    it()
                    readOnlyExternalRepositories = emptyList()
                }
            }
        }
        val root = RootDependencyNodeWithContext(
            templateContext = context,
            children = listOf(context.toMavenDependencyNode(coordinates.toMavenCoordinates(), false)),
        )
        return Resolver().resolveDependencies(root).root
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