/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.fus

import org.jetbrains.amper.agentdetection.Agent
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("cli-test-group-fus")
class CommandFusEventsTest : AbstractFusEventsTest() {

    @Test
    fun `command invoked and command result events are reported for a successful command`() = runSlowTest {
        val result = runCliWithFus(testProject("jvm-hello-world"), "show", "modules")

        val invoked = result.reportedFusEvents.assertSingle("ktc.command.invoked")
        assertEquals("ktc.command", invoked.group.id)
        invoked.assertData(
            "command" to "show",
            "subcommands" to ["modules"],
            "os_family" to expectedOsFamily,
            "cpu_arch" to expectedCpuArch,
            "caller_type" to "terminal",
            "interactive" to false,
            "ktc_version" to "LOCAL-SNAPSHOT",
            // 'oldest_installed_ktc_version' depends on the CLI versions that other tests left in the shared cache
            ignoredKeys = setOf("oldest_installed_ktc_version"),
        )
        // No CI system and no AI agent are reported, because the test environment hides them (see runCliWithFus).
        assertEquals(false, invoked.internal, "The test environment must not look like an internal JetBrains user")
        assertNotNull(invoked.session, "Regular events must have a session ID")
        // The project is not known yet when this event is reported.
        assertEquals(setOf("machine_id"), invoked.ids.keys)

        val completed = result.reportedFusEvents.assertSingle("ktc.command.result")
        completed.assertData(
            "command" to "show",
            "subcommands" to ["modules"],
            "result" to "success",
        )
        assertEquals(invoked.session, completed.session, "All events of a run must share the same session ID")
        assertEquals(setOf("machine_id", "project_id"), completed.ids.keys, "The project should be known by then")
    }

    @Test
    fun `command result event reports a failure when the command fails`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-explicit-api-violations"), "build",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        result.reportedFusEvents.assertSingle("ktc.command.result").assertData(
            "command" to "build",
            "subcommands" to emptyList<String>(),
            "result" to "failure",
        )
    }

    @Test
    fun `command invoked event reports the AI agent that runs the CLI`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-hello-world"), "show", "modules",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_AGENT_DETECTION_ENABLED", "true")
                put("AI_AGENT", Agent.ClaudeCode.aiAgentName)
            },
        )

        val invoked = result.reportedFusEvents.assertSingle("ktc.command.invoked")
        assertEquals("claude-code", invoked.data["ai_agent"])
        assertEquals("ai_agent", invoked.data["caller_type"])
    }

    @Test
    fun `command invoked event reports the IDE that runs the CLI`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-hello-world"), "show", "modules",
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_FUS_CALLING_IDE_PRODUCT_CODE", "IU")
            },
        )

        val invoked = result.reportedFusEvents.assertSingle("ktc.command.invoked")
        assertEquals("idea_ultimate", invoked.data["ide"])
        assertEquals("ide", invoked.data["caller_type"])
    }

    @Test
    fun `build command event is reported without filters`() = runSlowTest {
        val result = runCliWithFus(testProject("jvm-hello-world"), "build")

        val event = result.reportedFusEvents.assertSingle("ktc.command.invoked.build")
        assertEquals("1", event.data["module_count"])
        assertEquals(false, event.data["modules_filtered"])
        assertNull(event.data["requested_platforms"], "No platform was requested on the command line")
        assertNull(event.data["requested_build_types"], "No build variant was requested on the command line")
        assertEquals(event.data["platforms"], ["jvm"])

        result.reportedFusEvents.assertSingle("ktc.command.result").assertData(
            "command" to "build",
            "subcommands" to emptyList<String>(),
            "result" to "success",
        )
    }

    @Test
    fun `build command event reports the filters passed on the command line`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-multimodule-tests"), "build",
            "--module=one", "--platform=jvm", "--variant=debug",
        )

        result.reportedFusEvents.assertSingle("ktc.command.invoked.build").assertData(
            "module_count" to "1",
            "modules_filtered" to true,
            "platforms" to listOf("jvm"),
            "requested_platforms" to listOf("jvm"),
            "requested_build_types" to listOf("debug"),
        )
    }

    @Test
    fun `test command event is reported with the used test filter kinds`() = runSlowTest {
        val result = runCliWithFus(
            testProject("jvm-kotlin-test-smoke"), "test",
            // a specific class and a pattern, so that two different filter kinds are reported
            "--include-classes=apkg.ATest", "--include-classes=apkg.*Test",
        )

        result.reportedFusEvents.assertSingle("ktc.command.invoked.test").assertData(
            "module_count" to "1",
            "modules_filtered" to false,
            "platforms" to listOf("jvm"),
            "requested_platforms" to emptyList<String>(),
            // the filter values themselves must never be reported, only their kinds
            "used_test_filter_kinds" to listOf("include_class", "include_class_pattern"),
        )
    }

    @Test
    fun `run command event is reported`() = runSlowTest {
        val result = runCliWithFus(testProject("jvm-hello-world"), "run")

        result.reportedFusEvents.assertSingle("ktc.command.invoked.run").assertData(
            "product_type" to "jvm/app",
            "platform" to "jvm",
            // there is a single module and a single platform in this project, so nothing had to be specified
            "module_source" to "inferred",
            "platform_source" to "inferred",
            "compose_hot_reload" to "not_applicable",
        )
    }

    @Test
    fun `publish command event is reported`() = runSlowTest {
        val mavenLocalForTest = (nonRepositoryTempDir / "m2-publish").createDirectories()
        val result = runCliWithFus(
            testProject("jvm-publish"), "publish", "mavenLocal",
            amperJvmArgs = ["-Dmaven.repo.local=\"${mavenLocalForTest.absolutePathString()}\""],
        )

        result.reportedFusEvents.assertSingle("ktc.command.invoked.publish").assertData(
            "type" to "maven",
            "modules_filtered" to false,
            "transitive" to "auto",
        )
    }
}
