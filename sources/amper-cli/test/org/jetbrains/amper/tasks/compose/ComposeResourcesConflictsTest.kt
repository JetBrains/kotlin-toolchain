/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.cli.UserReadableError
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ComposeResourcesConflictsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `no conflict between origins providing different files`() {
        val library = origin("library", "library.generated.resources/font/icons.ttf")
        val module = origin("module", "app.generated.resources/font/icons.ttf")

        assertEquals(emptyMap(), findConflictingResources(listOf(library, module)))
    }

    @Test
    fun `no conflict between origins sharing only directories`() {
        val library = origin("library", "shared.generated.resources/font/icons.ttf")
        val module = origin("module", "shared.generated.resources/values/strings.xml")
        // an empty directory of the same name in both origins is not a conflict either
        (library.dir / "shared.generated.resources/drawable").createDirectories()
        (module.dir / "shared.generated.resources/drawable").createDirectories()

        assertEquals(emptyMap(), findConflictingResources(listOf(library, module)))
    }

    /**
     * The very same directory may be reported by several task dependencies (for instance, the same library reached
     * through two fragments). It contributes its files once, colliding with itself is not a conflict.
     */
    @Test
    fun `the same directory contributed twice does not conflict`() {
        val dir = composeResourcesDirWith("shared.generated.resources/font/icons.ttf")

        assertEquals(
            emptyMap(),
            findConflictingResources(
                listOf(
                    ComposeResourcesOrigin(description = "library", dir = dir),
                    ComposeResourcesOrigin(description = "library", dir = dir),
                )
            ),
        )
    }

    @Test
    fun `file provided by two libraries raises a conflict`() {
        val first = origin("first", "shared.generated.resources/font/icons.ttf")
        val second = origin("second", "shared.generated.resources/font/icons.ttf")

        assertEquals(
            mapOf("shared.generated.resources/font/icons.ttf" to listOf("first", "second")),
            findConflictingResources(listOf(first, second)),
        )
    }

    @Test
    fun `file provided by library and by local module conflicts`() {
        val library = origin("library", "shared.generated.resources/font/icons.ttf")
        val module = origin("module", "shared.generated.resources/font/icons.ttf")

        assertEquals(
            mapOf("shared.generated.resources/font/icons.ttf" to listOf("library", "module")),
            findConflictingResources(listOf(library, module)),
        )
    }

    @Test
    fun `all colliding files are reported at once in a stable order`() {
        val first = origin("first", "b.txt", "a.txt", "pkg/c.txt")
        val second = origin("second", "a.txt", "b.txt", "pkg/c.txt")
        val third = origin("third", "b.txt")

        assertEquals(
            listOf("a.txt", "b.txt", "pkg/c.txt"),
            findConflictingResources(listOf(first, second, third)).keys.toList(),
        )
        assertEquals(
            listOf("first", "second", "third"),
            findConflictingResources(listOf(first, second, third))["b.txt"],
        )
    }

    /**
     * Some libraries publish an empty Compose resources directory (koin does, for instance) such an archive
     * contributes nothing.
     */
    @Test
    fun `origin providing nothing does not raise conflict`() {
        val empty = origin("empty")
        val library = origin("library", "shared.generated.resources/font/icons.ttf")

        assertEquals(emptyMap(), findConflictingResources(listOf(empty, library)))
    }

    @Test
    fun `nothing is reported when there are no conflicts`() {
        checkNoConflictingResources(listOf(origin("library", "a.txt"), origin("module", "b.txt")))
    }

    /**
     * The same message is reported on every platform this is the whole point of sharing this check between them.
     */
    @Test
    fun `conflict is reported as a user readable error naming the file and every origin`() {
        val collidingFile = "shared.generated.resources/font/icons.ttf"
        val archive = tempDir / "library-1.0-kotlin_resources.kotlin_resources.zip"
        val error = assertFailsWith<UserReadableError> {
            checkNoConflictingResources(
                listOfNotNull(
                    ComposeResourcesOrigin.ofExternalArchive(
                        archive = archive,
                        extractedDir = rootWith("$COMPOSE_RESOURCES_DIR/$collidingFile"),
                    ),
                    ComposeResourcesOrigin.ofModule(
                        moduleName = "app",
                        mergedDir = rootWith("$COMPOSE_RESOURCES_DIR/$collidingFile"),
                    ),
                )
            )
        }

        // the path is reported the way it is packaged into the application, that is with the well-known prefix
        assertContains(error.message, "$COMPOSE_RESOURCES_DIR/$collidingFile")
        assertContains(error.message, "library-1.0-kotlin_resources.kotlin_resources.zip")
        assertContains(error.message, "app")
    }

    /**
     * A KMP resources archive may declare an arbitrary layout, only its Compose resources are ours to package.
     * Anything else must not even be looked at, or a dependency could shadow the files of the application itself
     * (the index.html of a wasm application, for instance).
     */
    @Test
    fun `only the compose resources of an archive are consumed`() {
        val extractedDir = rootWith("index.html", "$COMPOSE_RESOURCES_DIR/library.generated.resources/font/icons.ttf")

        val origin = ComposeResourcesOrigin.ofExternalArchive(archive = anArchive(), extractedDir = extractedDir)

        assertEquals(extractedDir / COMPOSE_RESOURCES_DIR, origin?.dir)
        assertEquals(emptyMap(), findConflictingResources(listOfNotNull(origin)))
    }

    /**
     * Two dependencies shipping such a file at the same path are not in conflict, neither of them is packaged.
     */
    @Test
    fun `files of an archive outside of the compose resources do not conflict`() {
        val first = ComposeResourcesOrigin.ofExternalArchive(
            archive = anArchive(),
            extractedDir = rootWith("index.html", "$COMPOSE_RESOURCES_DIR/first.generated.resources/a.txt"),
        )
        val second = ComposeResourcesOrigin.ofExternalArchive(
            archive = anArchive(),
            extractedDir = rootWith("index.html", "$COMPOSE_RESOURCES_DIR/second.generated.resources/b.txt"),
        )

        assertEquals(emptyMap(), findConflictingResources(listOfNotNull(first, second)))
    }

    @Test
    fun `archive without compose resources provides no origin`() {
        val extractedDir = rootWith("index.html", "some/other/layout.txt")

        assertNull(ComposeResourcesOrigin.ofExternalArchive(archive = anArchive(), extractedDir = extractedDir))
    }

    @Test
    fun `compose resources of a module are taken from its packaging directory`() {
        val mergedDir = rootWith("$COMPOSE_RESOURCES_DIR/app.generated.resources/font/icons.ttf")

        val origin = ComposeResourcesOrigin.ofModule(moduleName = "app", mergedDir = mergedDir)

        assertEquals(mergedDir / COMPOSE_RESOURCES_DIR, origin?.dir)
    }

    @Test
    fun `module without compose resources provides no origin`() {
        assertNull(ComposeResourcesOrigin.ofModule(moduleName = "app", mergedDir = rootWith()))
    }

    private var roots = 0
    private var archives = 0

    private fun origin(description: String, vararg files: String) =
        ComposeResourcesOrigin(description = description, dir = composeResourcesDirWith(*files))

    /**
     * The Compose resources directory of an origin, holding the given [files] (paths relative to it).
     */
    private fun composeResourcesDirWith(vararg files: String): Path {
        val rootDir = rootWith(
            *files.map { "$COMPOSE_RESOURCES_DIR/$it" }.toTypedArray()
        )
        return (rootDir / COMPOSE_RESOURCES_DIR).createDirectories()
    }

    /**
     * The root of an origin, that is, an extracted KMP resources archive or the merged resources of a module.
     */
    private fun rootWith(vararg files: String): Path {
        // the directory is not named after the origin: a description may quote the name of an archive or of a module
        val dir = (tempDir / "root-${roots++}").createDirectories()
        files.forEach { (dir / it).createParentDirectories().writeText(it) }
        return dir
    }

    private fun anArchive(): Path = tempDir / "library-${archives++}-kotlin_resources.kotlin_resources.zip"
}
