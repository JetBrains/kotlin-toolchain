/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertLogContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.server.RouteHandler
import org.jetbrains.amper.test.server.withRecordingHttpServer
import org.junit.jupiter.api.Tag
import org.slf4j.event.Level
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("cli-test-group-publication")
class KlibsIoNotificationTest : CliTestBase() {

    @Test
    fun `sends coordinates to klibs io for KMP modules only`() = runSlowTest {
        val routes = configureRoutes()

        val requests = withRecordingHttpServer(routes) { baseUrl ->
            val result = runPublish(
                baseUrl = baseUrl,
                testProjectName = "kmp-publish-multimodule",
            )
            result.assertLogContains("Notifying klibs.io about kmp-lib-A", Level.INFO)
            result.assertLogContains("Notifying klibs.io about kmp-lib-B", Level.INFO)
        }

        assertEquals(8, requests.size)
        val klibsRequests = requests.filter { it.path == "/notify/artifacts" }
        assertEquals(2, klibsRequests.size)
        assertContains(
            klibsRequests.map { it.body },
            """{"groupId":"amper.test.kmp-publish-multimodule","artifactId":"kmp-lib-A","version":"2.2"}"""
        )
        assertContains(
            klibsRequests.map { it.body },
            """{"groupId":"amper.test.kmp-publish-multimodule","artifactId":"kmp-lib-B","version":"2.2"}"""
        )
    }

    @Test
    fun `sends module coordinates to klibs io for selected module only`() = runSlowTest {
        val routes = configureRoutes()

        val requests = withRecordingHttpServer(routes) { baseUrl ->
            val result = runPublish(
                baseUrl = baseUrl,
                testProjectName = "kmp-publish-multimodule",
                testModuleName = "kmp-lib-A",
            )
            result.assertLogContains("Notifying klibs.io", Level.INFO)
            result.assertStdoutContains("Successfully notified klibs.io")
        }

        assertEquals(3, requests.size)
        val klibsRequest = requests.single { it.path == "/notify/artifacts" }
        assertEquals("POST", klibsRequest.method)
        assertEquals(
            """{"groupId":"amper.test.kmp-publish-multimodule","artifactId":"kmp-lib-A","version":"2.2"}""",
            klibsRequest.body,
        )
    }

    @Test
    fun `does not send coordinates to klibs io if notifyKlibsIo is not set`() = runSlowTest {
        val routes = configureRoutes()

        val requests = withRecordingHttpServer(routes) { baseUrl ->
            runPublish(
                baseUrl = baseUrl,
                testProjectName = "kmp-publish-with-signing",
            )
        }

        assertEquals(2, requests.size)
        assertTrue { requests.none { it.path == "/notify/artifacts" } }
    }

    @Test
    fun `does not send coordinates to klibs io after failed publishing`() = runSlowTest {
        val routes = configureRoutes(
            mavenCentralStatus = "FAILED"
        )

        val requests = withRecordingHttpServer(routes) { baseUrl ->
            runPublish(
                baseUrl = baseUrl,
                testProjectName = "kmp-publish-multimodule",
                testModuleName = "kmp-lib-A",
                expectedExitCode = 1,
                assertEmptyStdErr = false,
            )
        }

        assertEquals(2, requests.size)
        assertTrue { requests.none { it.path == "/notify/artifacts" } }
    }

    @Test
    fun `does not send coordinates to klibs io after publishing to repository that is not MavenCentral`() =
        runSlowTest {
            val routes = configureRoutes()

            val requests = withRecordingHttpServer(routes) { baseUrl ->
                runPublish(
                    baseUrl = baseUrl,
                    testProjectName = "kmp-publish-multimodule",
                    repository = "mavenLocal",
                )
            }

            assertEquals(0, requests.size)
        }

    @Test
    fun `warns and does not fail the build when klibs io responds with an error`() = runSlowTest {
        val routes = configureRoutes(
            klibsIoStatus = 400
        )

        val requests = withRecordingHttpServer(routes) { baseUrl ->
            val result = runPublish(
                baseUrl = baseUrl,
                testProjectName = "kmp-publish-multimodule",
                testModuleName = "kmp-lib-A",
            )
            result.assertLogContains("Notifying klibs.io", Level.INFO)
            result.assertLogContains("Failed to notify klibs.io", Level.WARN)
        }

        assertEquals(3, requests.size)
        val klibsRequest = requests.single { it.path == "/notify/artifacts" }
        assertEquals("POST", klibsRequest.method)
        assertEquals(
            """{"groupId":"amper.test.kmp-publish-multimodule","artifactId":"kmp-lib-A","version":"2.2"}""",
            klibsRequest.body,
        )
    }

    @Test
    fun `warns and does not fail the build when klibs io is unreachable`() = runSlowTest {
        val routes = configureRoutes()

        val requests = withRecordingHttpServer(routes) { baseUrl ->
            val result = runPublish(
                baseUrl = baseUrl,
                testProjectName = "kmp-publish-multimodule",
                testModuleName = "kmp-lib-A",
                klibsIoUrl = "https://klibs.io.invalid/notify/artifacts",
            )
            result.assertLogContains("Notifying klibs.io", Level.INFO)
            result.assertLogContains("Failed to reach klibs.io", Level.WARN)
        }

        assertEquals(2, requests.size)
    }

    private fun configureRoutes(
        mavenCentralStatus: String = "VALIDATED",
        klibsIoStatus: Int = 200,
    ): List<RouteHandler> {
        val deploymentId = "00000000-0000-0000-0000-000000000000"

        return [
            RouteHandler(
                path = "/notify/artifacts",
                responseStatus = klibsIoStatus,
                responseBody = "Mock klibs.io response",
            ),
            RouteHandler(
                path = "/publisher/upload",
                responseStatus = 200,
                responseBody = deploymentId,
            ),
            RouteHandler(
                path = "/publisher/status",
                responseStatus = 200,
                responseBody = """{
                        "deploymentId": "$deploymentId",
                        "deploymentName": "mock deployment",
                        "deploymentState": "$mavenCentralStatus"
                    }""".trimIndent(),
                responseHeaders = mapOf("Content-Type" to "application/json"),
            ),
        ]
    }

    /**
     * Configures environment and runs `kotlin publish` command.
     *
     * @param testModuleName if not specified, all modules are published
     */
    private suspend fun runPublish(
        baseUrl: String,
        testProjectName: String,
        testModuleName: String? = null,
        repository: String = "mavenCentral",
        klibsIoUrl: String = "$baseUrl/notify/artifacts",
        expectedExitCode: Int? = 0,
        assertEmptyStdErr: Boolean = true,
    ): AmperCliResult {
        val projectDir = testProject(testProjectName)
        val moduleArgs = if (testModuleName != null) arrayOf("-m", testModuleName) else []

        return runCli(
            projectDir = projectDir,
            "publish", repository, *moduleArgs,
            expectedExitCode = expectedExitCode,
            assertEmptyStdErr = assertEmptyStdErr,
            amperJvmArgs = ["-Dmaven.repo.local=\"${tempRoot.resolve(".m2.test").absolutePathString()}\""],
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_KLIBS_IO_API_URL", klibsIoUrl)
                put("KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_API_URL", baseUrl)
                put("KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME", "mock_user")
                put("KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD", "mock_password")
                put(
                    "KOTLIN_TOOLCHAIN_SIGNING_KEY",
                    """
                    -----BEGIN PGP PRIVATE KEY BLOCK-----
                    Comment: Mock Secret Key
                    Comment: https://www.ietf.org/id/draft-bre-openpgp-samples-01.html
    
                    lFgEXEcE6RYJKwYBBAHaRw8BAQdArjWwk3FAqyiFbFBKT4TzXcVBqPTB3gmzlC/U
                    b7O1u10AAP9XBeW6lzGOLx7zHH9AsUDUTb2pggYGMzd0P3ulJ2AfvQ4RtCZBbGlj
                    ZSBMb3ZlbGFjZSA8YWxpY2VAb3BlbnBncC5leGFtcGxlPoiQBBMWCAA4AhsDBQsJ
                    CAcCBhUKCQgLAgQWAgMBAh4BAheAFiEE64W7X6M6deFelE5j8jFVDE9H444FAl2l
                    nzoACgkQ8jFVDE9H447pKwD6A5xwUqIDprBzrHfahrImaYEZzncqb25vkLV2arYf
                    a78A/R3AwtLQvjxwLDuzk4dUtUwvUYibL2sAHwj2kGaHnfICnF0EXEcE6RIKKwYB
                    BAGXVQEFAQEHQEL/BiGtq0k84Km1wqQw2DIikVYrQrMttN8d7BPfnr4iAwEIBwAA
                    /3/xFPG6U17rhTuq+07gmEvaFYKfxRB6sgAYiW6TMTpQEK6IeAQYFggAIBYhBOuF
                    u1+jOnXhXpROY/IxVQxPR+OOBQJcRwTpAhsMAAoJEPIxVQxPR+OOWdABAMUdSzpM
                    hzGs1O0RkWNQWbUzQ8nUOeD9wNbjE3zR+yfRAQDbYqvtWQKN4AQLTxVJN5X5AWyb
                    Pnn+We1aTBhaGa86AQ==
                    =n8OM
                    -----END PGP PRIVATE KEY BLOCK-----
                    """.trimIndent()
                )
            },
        )
    }
}
