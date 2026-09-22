/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.projectwizard

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitRepositoryInitializerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `initializes the repository on a main branch`() = runBlocking {
        val result = initGitRepository(tempDir)

        assertEquals(GitInitResult.Initialized, result)
        assertEquals("ref: refs/heads/main", (tempDir / ".git" / "HEAD").readText().trim())
    }

    @Test
    fun `normalizes generated files according to gitattributes`() = runBlocking {
        (tempDir / ".gitattributes").writeText("* text=auto eol=lf\n*.bat text eol=crlf\n")
        (tempDir / "script.bat").writeText("echo first\necho second\n")
        (tempDir / "source.kt").writeText("first\r\nsecond\r\n")

        val result = initGitRepository(tempDir)

        assertEquals(GitInitResult.Initialized, result)
        assertEquals("echo first\r\necho second\r\n", (tempDir / "script.bat").readText())
        assertEquals("first\nsecond\n", (tempDir / "source.kt").readText())
        Git.open(tempDir.toFile()).use { git ->
            val addedFiles = git.status().call().added
            assertTrue(addedFiles.isEmpty(), "Line-ending normalization must not stage generated files: $addedFiles")
        }
    }
}
