/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import org.jetbrains.amper.cli.UserReadableError
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@DisabledOnOs(OS.WINDOWS, disabledReason = "Creating symlinks requires privileges on Windows")
class ProjectGenerationSymlinksTest {
    @TempDir
    lateinit var tempDir: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `detects and replaces generated-file symlinks without touching their targets`(dangling: Boolean) {
        val outputDir = (tempDir / "project").createDirectories()
        val target = tempDir / "outside.txt"
        if (!dangling) target.writeText("keep me")
        val link = (outputDir / "project.yaml").createSymbolicLinkPointingTo(target)

        val error = assertFailsWith<UserReadableError> {
            checkTemplateFilesConflicts(["project.yaml"], outputDir)
        }
        assertContains(error.message, "project.yaml")
        clearConflictingPaths(["project.yaml"], outputDir)

        assertFalse(link.exists(NOFOLLOW_LINKS))
        if (dangling) assertFalse(target.exists()) else assertEquals("keep me", target.readText())
    }

    @Test
    fun `overwriting a symlinked ancestor never deletes external files`() {
        val outputDir = (tempDir / "project").createDirectories()
        val outsideDir = (tempDir / "outside").createDirectories()
        val nestedDir = (outsideDir / "nested").createDirectories()
        val sentinel = nestedDir / "main.kt"
        sentinel.writeText("keep me")
        val link = (outputDir / "src").createSymbolicLinkPointingTo(outsideDir)
        val paths = listOf("src/nested/main.kt", "src/another.kt")

        val error = assertFailsWith<UserReadableError> {
            checkTemplateFilesConflicts(paths, outputDir)
        }
        assertContains(error.message, "src")
        clearConflictingPaths(paths, outputDir)

        assertFalse(link.isSymbolicLink())
        assertEquals("keep me", sentinel.readText())
    }

    @Test
    fun `detects dangling symlinks in ancestor paths`() {
        val outputDir = (tempDir / "project").createDirectories()
        val target = tempDir / "missing"
        val link = (outputDir / "src").createSymbolicLinkPointingTo(target)

        assertFailsWith<UserReadableError> {
            checkTemplateFilesConflicts(["src/nested/main.kt"], outputDir)
        }
        clearConflictingPaths(["src/nested/main.kt"], outputDir)
        assertFalse(link.exists(NOFOLLOW_LINKS))
        assertFalse(target.exists())
    }
}
