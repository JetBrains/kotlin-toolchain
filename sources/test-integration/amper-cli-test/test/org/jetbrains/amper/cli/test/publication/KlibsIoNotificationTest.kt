/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertLogContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.server.withRecordingHttpServer
import org.junit.jupiter.api.Tag
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("cli-test-group-publication")
class KlibsIoNotificationTest : CliTestBase() {

    @Test
    fun `notify-only sends the module coordinates to klibs io without publishing`() = runSlowTest {
        val requests = withRecordingHttpServer(responseBody = ACK_BODY) { baseUrl ->
            val projectDir = testProject("kmp-publish-with-signing")
            projectDir.configureYaml("$baseUrl/notify/artifacts")
            runCli(projectDir = projectDir, "publish", "mavenCentral", "--notify-only")
        }

        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/notify/artifacts", request.path)
        assertEquals(
            """{"groupId":"amper.test.kmp-publish-with-signing","artifactId":"artifactName","version":"2.2"}""",
            request.body,
        )
    }

    @Test
    fun `notify-only fails the build when klibs io responds with an error`() = runSlowTest {
        lateinit var result: AmperCliResult
        withRecordingHttpServer(responseStatus = 500, responseBody = "internal error") { baseUrl ->
            val projectDir = testProject("kmp-publish-with-signing")
            projectDir.configureYaml("$baseUrl/notify/artifacts")
            result = runCli(
                projectDir = projectDir,
                "publish", "mavenCentral", "--notify-only",
                expectedExitCode = 1,
                assertEmptyStdErr = false,
            )
        }
        result.assertLogContains("klibs.io", Level.ERROR)
    }

    @Test
    fun `notify-only fails when the repository is not Maven Central`() = runSlowTest {
        runCli(
            projectDir = testProject("kmp-publish-with-signing"),
            "publish", "mavenLocal", "--notify-only",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
    }

    @Test
    fun `notify-only fails when no selected module has Maven Central publishing enabled`() = runSlowTest {
        runCli(
            projectDir = testProject("jvm-publish"),
            "publish", "mavenCentral", "--notify-only",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
    }

    @Test
    fun `notify-only fails for a non-multiplatform module`() = runSlowTest {
        lateinit var result: AmperCliResult
        val requests = withRecordingHttpServer(responseBody = ACK_BODY) { baseUrl ->
            val projectDir = testProject("jvm-publish-with-signing")
            projectDir.configureYaml("$baseUrl/notify/artifacts")
            result = runCli(
                projectDir = projectDir,
                "publish", "mavenCentral", "--notify-only",
                expectedExitCode = 1,
                assertEmptyStdErr = false,
            )
        }
        assertTrue(
            requests.isEmpty(),
            "Expected no klibs.io notification for a non-multiplatform module, but got: $requests"
        )
        result.assertLogContains("klibs.io", Level.ERROR)
    }

    @Test
    fun `notify-only fails the build when klibs io is unreachable`() = runSlowTest {
        val projectDir = testProject("kmp-publish-with-signing")
        projectDir.configureYaml("http://klibs.invalid.api.so.it.is.unreachable/notify/artifacts")

        val result = runCli(
            projectDir = projectDir,
            "publish", "mavenCentral", "--notify-only",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.assertLogContains("klibs.io", Level.ERROR)
    }

    @Test
    fun `the notify task depends on the Maven Central publish task when automatic notification is enabled`() =
        runSlowTest {
            val projectDir = testProject("kmp-publish-with-signing")
            projectDir.configureYaml(automatic = true)

            val result = runCli(projectDir = projectDir, "show", "tasks")

            result.assertStdoutContains("notifyKlibsIo -> :kmp-publish-with-signing:publishToMavenCentral")
        }

    @Test
    fun `the notify task is not registered when automatic notification is disabled`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("kmp-publish-with-signing"),
            "show", "tasks",
        )

        result.assertStdoutDoesNotContain("notifyKlibsIo")
    }

    private fun Path.configureYaml(
        apiUrl: String? = null,
        automatic: Boolean = false,
    ) {
        val moduleYaml = this / "module.yaml"
        val content = buildString {
            appendLine("    mavenCentral:")
            appendLine("      enabled: true")
            appendLine("      notifyKlibsIo:")
            if (apiUrl != null) {
                appendLine("        apiUrl: $apiUrl")
            }
            if (automatic) {
                appendLine("        automatic: true")
            }
        }

        moduleYaml.writeText(
            moduleYaml.readText().replace("    mavenCentral: enabled", content)
        )
    }

    private companion object {
        const val ACK_BODY = "The indexing request has been successfully added to the klibs.io queue."
    }
}
