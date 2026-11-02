/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.fus

import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("cli-test-group-fus")
class ProjectFusEventsTest : AbstractFusEventsTest() {

    @Test
    fun `project created event is reported by the new command`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "new-project").createDirectories()
        val result = runCliWithFus(
            projectDir = workDir,
            "new", "--no-ai", "--no-git", "myapp", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val event = result.reportedFusEvents.assertSingle("ktc.project.created")
        assertEquals("ktc.project", event.group.id)
        event.assertData(
            "command" to "new",
            "template_id" to "kotlin-multiplatform-app",
            "target_platforms" to listOf("desktop"),
            "configured_ai_agents" to emptyList<String>(),
            "ai_enabled" to false,
            "git_enabled" to false,
        )
    }

    @Test
    fun `project created event is reported by the init command`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "new-project").createDirectories()
        val result = runCliWithFus(
            projectDir = workDir,
            "init", "--no-ai", "--from-template=jvm-cli",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val event = result.reportedFusEvents.assertSingle("ktc.project.created")
        assertEquals("ktc.project", event.group.id)
        event.assertData(
            "command" to "init",
            "template_id" to "jvm-cli",
            "configured_ai_agents" to emptyList<String>(),
            "ai_enabled" to false,
            "git_enabled" to true,
        )
    }
}
