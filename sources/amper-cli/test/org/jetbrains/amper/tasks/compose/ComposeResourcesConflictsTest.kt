/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.test.runTestWithMdc
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
    fun `no conflict between origins providing different files`() = runTestWithMdc {
        val library = libraryOrigin("library.zip", "library.generated.resources/font/icons.ttf")
        val module = moduleOrigin("app", "app.generated.resources/font/icons.ttf")

        assertEquals(emptyMap(), findConflictingResourcesDescriptions(listOf(library, module)))
    }

    @Test
    fun `no conflict between origins sharing only directories`() = runTestWithMdc {
        val library = libraryOrigin("library.zip", "shared.generated.resources/font/icons.ttf")
        val module = moduleOrigin("app", "shared.generated.resources/values/strings.xml")
        // an empty directory of the same name in both origins is not a conflict either
        (library.dir / "shared.generated.resources/drawable").createDirectories()
        (module.dir / "shared.generated.resources/drawable").createDirectories()

        assertEquals(emptyMap(), findConflictingResourcesDescriptions(listOf(library, module)))
    }

    /**
     * The very same directory may be reported by several task dependencies (for instance, the same library reached
     * through two fragments). It contributes its files once, conflicting with itself is not a conflict.
     */
    @Test
    fun `the same directory contributed twice does not conflict`() = runTestWithMdc {
        val root = composeResourcesRootWith("shared.generated.resources/font/icons.ttf")

        assertEquals(
            emptyMap(),
            findConflictingResourcesDescriptions(
                listOfNotNull(
                    ModuleComposeResources.of(moduleName = "app", mergedDir = root),
                    ModuleComposeResources.of(moduleName = "app", mergedDir = root),
                )
            ),
        )
    }

    @Test
    fun `file provided by two libraries raises a conflict`() = runTestWithMdc {
        val first = libraryOrigin("first.zip", "shared.generated.resources/font/icons.ttf")
        val second = libraryOrigin("second.zip", "shared.generated.resources/font/icons.ttf")

        assertEquals(
            mapOf(
                "shared.generated.resources/font/icons.ttf" to listOf(
                    "the KMP resources archive 'first.zip'",
                    "the KMP resources archive 'second.zip'",
                )
            ),
            findConflictingResourcesDescriptions(listOf(first, second)),
        )
    }

    @Test
    fun `file provided by library and by local module conflicts`() = runTestWithMdc {
        val library = libraryOrigin("library.zip", "shared.generated.resources/font/icons.ttf")
        val module = moduleOrigin("app", "shared.generated.resources/font/icons.ttf")

        assertEquals(
            mapOf(
                "shared.generated.resources/font/icons.ttf" to listOf(
                    "module 'app'",
                    "the KMP resources archive 'library.zip'",
                )
            ),
            findConflictingResourcesDescriptions(listOf(library, module)),
        )
    }

    @Test
    fun `all conflicting files are reported at once in a stable order`() = runTestWithMdc {
        val first = moduleOrigin("first", "b.txt", "a.txt", "pkg/c.txt")
        val second = moduleOrigin("second", "a.txt", "b.txt", "pkg/c.txt")
        val third = moduleOrigin("third", "b.txt")

        assertEquals(
            listOf("a.txt", "b.txt", "pkg/c.txt"),
            findConflictingResourcesDescriptions(listOf(first, second, third)).keys.toList(),
        )
        assertEquals(
            listOf("module 'first'", "module 'second'", "module 'third'"),
            findConflictingResourcesDescriptions(listOf(first, second, third))["b.txt"],
        )
    }

    /**
     * Some libraries publish an empty Compose resources directory (koin does, for instance) such an archive
     * contributes nothing.
     */
    @Test
    fun `origin providing nothing does not raise conflict`() = runTestWithMdc {
        val empty = libraryOrigin("empty.zip")
        val library = libraryOrigin("library.zip", "shared.generated.resources/font/icons.ttf")

        assertEquals(emptyMap(), findConflictingResourcesDescriptions(listOf(empty, library)))
    }

    @Test
    fun `nothing is reported when there are no conflicts`() = runTestWithMdc {
        checkNoConflictingResources(listOf(libraryOrigin("library.zip", "a.txt"), moduleOrigin("app", "b.txt")))
    }

    /**
     * The same message is reported on every platform this is the whole point of sharing this check between them.
     */
    @Test
    fun `conflict is reported as a user readable error naming the file and every origin`() = runTestWithMdc {
        val conflictingFile = "shared.generated.resources/font/icons.ttf"
        val error = assertFailsWith<UserReadableError> {
            checkNoConflictingResources(
                listOf(
                    libraryOrigin("library-1.0-kotlin_resources.kotlin_resources.zip", conflictingFile),
                    moduleOrigin("app", conflictingFile),
                )
            )
        }

        // the path is reported the way it is packaged into the application, that is with the well-known prefix
        assertContains(error.message, "$COMPOSE_RESOURCES_DIR/$conflictingFile")
        assertContains(error.message, "library-1.0-kotlin_resources.kotlin_resources.zip")
        assertContains(error.message, "app")
    }

    /**
     * A KMP resources archive may declare an arbitrary layout, only its Compose resources are ours to package.
     * Anything else must not even be looked at, or a dependency could shadow the files of the application itself
     * (the index.html of a wasm application, for instance).
     */
    @Test
    fun `only the compose resources of an archive are consumed`() = runTestWithMdc {
        val extractedDir = rootWith("index.html", "$COMPOSE_RESOURCES_DIR/library.generated.resources/font/icons.ttf")

        val origin = ExternalLibraryComposeResources.of(archive = anArchive(), extractedDir = extractedDir)

        assertEquals(extractedDir / COMPOSE_RESOURCES_DIR, origin?.dir)
        assertEquals(emptyMap(), findConflictingResourcesDescriptions(listOfNotNull(origin)))
    }

    /**
     * Two dependencies shipping such a file at the same path are not in conflict, neither of them is packaged.
     */
    @Test
    fun `files of an archive outside of the compose resources do not conflict`() = runTestWithMdc {
        val first = ExternalLibraryComposeResources.of(
            archive = anArchive(),
            extractedDir = rootWith("index.html", "$COMPOSE_RESOURCES_DIR/first.generated.resources/a.txt"),
        )
        val second = ExternalLibraryComposeResources.of(
            archive = anArchive(),
            extractedDir = rootWith("index.html", "$COMPOSE_RESOURCES_DIR/second.generated.resources/b.txt"),
        )

        assertEquals(emptyMap(), findConflictingResourcesDescriptions(listOfNotNull(first, second)))
    }

    @Test
    fun `archive without compose resources provides no origin`() = runTestWithMdc {
        val extractedDir = rootWith("index.html", "some/other/layout.txt")

        assertNull(ExternalLibraryComposeResources.of(archive = anArchive(), extractedDir = extractedDir))
    }

    @Test
    fun `compose resources of a module are taken from its packaging directory`() = runTestWithMdc {
        val mergedDir = rootWith("$COMPOSE_RESOURCES_DIR/app.generated.resources/font/icons.ttf")

        val origin = ModuleComposeResources.of(moduleName = "app", mergedDir = mergedDir)

        assertEquals(mergedDir / COMPOSE_RESOURCES_DIR, origin?.dir)
    }

    @Test
    fun `module without compose resources provides no origin`() {
        assertNull(ModuleComposeResources.of(moduleName = "app", mergedDir = rootWith()))
    }

    @Test
    fun `fragment without prepared compose resources provides no origin`() = runTestWithMdc {
        val origin = FragmentComposeResources.of(
            fragmentName = "jvm",
            refinedFragments = setOf("common"),
            preparedDir = tempDir / "nowhere",
        )

        assertNull(origin)
    }

    private var roots = 0
    private var archives = 0

    /**
     * The Compose resources of a library published as the KMP resources archive named [archiveName], holding the
     * given [files] (paths relative to the Compose resources directory of the archive).
     */
    private fun libraryOrigin(archiveName: String, vararg files: String) =
        ExternalLibraryComposeResources.of(
            archive = tempDir / archiveName,
            extractedDir = composeResourcesRootWith(*files),
        ) ?: error("The archive '$archiveName' should provide an origin")

    /**
     * The merged Compose resources of the module named [moduleName], holding the given [files] (paths relative to
     * the Compose resources directory of the module).
     */
    private fun moduleOrigin(moduleName: String, vararg files: String) =
        ModuleComposeResources.of(moduleName = moduleName, mergedDir = composeResourcesRootWith(*files))
            ?: error("The module '$moduleName' should provide an origin")

    /**
     * The root of an origin holding the given [files] in its Compose resources directory, which is always created,
     * even when there is no file at all (some libraries do publish an empty one).
     */
    private fun composeResourcesRootWith(vararg files: String): Path {
        val rootDir = rootWith(*files.map { "$COMPOSE_RESOURCES_DIR/$it" }.toTypedArray())
        (rootDir / COMPOSE_RESOURCES_DIR).createDirectories()
        return rootDir
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
