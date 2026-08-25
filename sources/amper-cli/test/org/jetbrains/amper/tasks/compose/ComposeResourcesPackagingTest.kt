/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.cli.UserReadableError
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComposeResourcesPackagingTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resources of every origin are packaged under the compose resources directory`() = runBlocking {
        val outputDir = outputDir()

        packageComposeResources(
            origins = listOf(
                origin("library", "library.generated.resources/font/icons.ttf"),
                origin("module", "app.generated.resources/values/strings.xml"),
            ),
            outputDir = outputDir,
        )

        assertEquals(
            listOf(
                "app.generated.resources/values/strings.xml",
                "library.generated.resources/font/icons.ttf",
            ),
            packagedFiles(outputDir),
        )
    }

    /**
     * Directories are not in conflict, they are merged. Copying is strict, so this must not fail either.
     */
    @Test
    fun `origins sharing a directory are merged`() = runBlocking {
        val outputDir = outputDir()

        packageComposeResources(
            origins = listOf(
                origin("first", "shared.generated.resources/font/icons.ttf"),
                origin("second", "shared.generated.resources/values/strings.xml"),
            ),
            outputDir = outputDir,
        )

        assertEquals(
            listOf(
                "shared.generated.resources/font/icons.ttf",
                "shared.generated.resources/values/strings.xml",
            ),
            packagedFiles(outputDir),
        )
    }

    @Test
    fun `conflict between origins is reported`() {
        val error = assertFailsWith<UserReadableError> {
            runBlocking {
                packageComposeResources(
                    origins = listOf(
                        origin("first", "shared.generated.resources/font/icons.ttf"),
                        origin("second", "shared.generated.resources/font/icons.ttf"),
                    ),
                    outputDir = outputDir(),
                )
            }
        }

        assertContains(error.message, "shared.generated.resources/font/icons.ttf")
    }

    /**
     * The very same directory may be reported by several task dependencies (the same library reached through two
     * fragments, for instance). Copying is strict, so it has to be packaged exactly once.
     */
    @Test
    fun `same directory contributed twice is packaged once`() = runBlocking {
        val dir = composeResourcesDirWith("shared.generated.resources/font/icons.ttf")
        val outputDir = outputDir()

        packageComposeResources(
            origins = listOf(
                ComposeResourcesOrigin(description = "first", dir = dir),
                ComposeResourcesOrigin(description = "second", dir = dir),
            ),
            outputDir = outputDir,
        )

        assertEquals(listOf("shared.generated.resources/font/icons.ttf"), packagedFiles(outputDir))
    }

    @Test
    fun `no directory is created when there are no compose resources`() = runBlocking {
        val outputDir = outputDir()

        packageComposeResources(origins = emptyList(), outputDir = outputDir)

        assertTrue((outputDir / COMPOSE_RESOURCES_DIR).notExists())
    }

    private var dirs = 0

    private fun outputDir(): Path = (tempDir / "output-${dirs++}").createDirectories()

    private fun origin(description: String, vararg files: String) =
        ComposeResourcesOrigin(description = description, dir = composeResourcesDirWith(*files))

    /**
     * The Compose resources directory of an origin, holding the given [files] (paths relative to it).
     */
    private fun composeResourcesDirWith(vararg files: String): Path {
        val dir = (tempDir / "root-${dirs++}" / COMPOSE_RESOURCES_DIR).createDirectories()
        files.forEach { (dir / it).createParentDirectories().writeText(it) }
        return dir
    }

    /**
     * The files packaged into [outputDir], as paths relative to its [COMPOSE_RESOURCES_DIR] directory.
     */
    private fun packagedFiles(outputDir: Path): List<String> {
        val composeResourcesDir = outputDir / COMPOSE_RESOURCES_DIR
        return composeResourcesDir.walk()
            .filter { it.isRegularFile() }
            .map { it.relativeTo(composeResourcesDir).invariantSeparatorsPathString }
            .sorted()
            .toList()
    }
}
