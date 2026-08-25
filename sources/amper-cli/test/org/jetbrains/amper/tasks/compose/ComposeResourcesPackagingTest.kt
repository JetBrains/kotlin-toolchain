/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.compose

import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.test.TempDirExtension
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeResourcesPackagingTest {

    @RegisterExtension
    private val tempDirExtension = TempDirExtension()

    private val tempDir: Path
        get() = tempDirExtension.path

    @Test
    fun `resources of every origin are packaged under the compose resources directory`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResources(
            origins = listOf(
                libraryOrigin("library.zip", "library.generated.resources/font/icons.ttf"),
                moduleOrigin("app", "app.generated.resources/values/strings.xml"),
            ),
            outputDir = outputDir,
        )

        assertEquals(
            listOf(
                "app.generated.resources/values/strings.xml",
                "library.generated.resources/font/icons.ttf",
            ),
            filesIn(outputDir / COMPOSE_RESOURCES_DIR),
        )
    }

    /**
     * Directories are not in conflict, they are merged. Copying is strict, so this must not fail either.
     */
    @Test
    fun `origins sharing a directory are merged`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResources(
            origins = listOf(
                moduleOrigin("first", "shared.generated.resources/font/icons.ttf"),
                moduleOrigin("second", "shared.generated.resources/values/strings.xml"),
            ),
            outputDir = outputDir,
        )

        assertEquals(
            listOf(
                "shared.generated.resources/font/icons.ttf",
                "shared.generated.resources/values/strings.xml",
            ),
            filesIn(outputDir / COMPOSE_RESOURCES_DIR),
        )
    }

    @Test
    fun `conflict between origins is reported`() = runTestWithMdc {
        val error = assertFailsWith<UserReadableError> {
            packageComposeResources(
                origins = listOf(
                    moduleOrigin("first", "shared.generated.resources/font/icons.ttf"),
                    moduleOrigin("second", "shared.generated.resources/font/icons.ttf"),
                ),
                outputDir = outputDir(),
            )
        }

        assertContains(error.message, "shared.generated.resources/font/icons.ttf")
    }

    /**
     * The very same directory may be reported by several task dependencies (the same library reached through two
     * fragments, for instance). Copying is strict, so it has to be packaged exactly once.
     */
    @Test
    fun `same directory contributed twice is packaged once`() = runTestWithMdc {
        val root = composeResourcesRootWith("shared.generated.resources/font/icons.ttf")
        val outputDir = outputDir()

        packageComposeResources(
            origins = listOfNotNull(
                ModuleComposeResources.of(moduleName = "app", mergedDir = root),
                ModuleComposeResources.of(moduleName = "app", mergedDir = root),
            ),
            outputDir = outputDir,
        )

        assertEquals(listOf("shared.generated.resources/font/icons.ttf"), filesIn(outputDir / COMPOSE_RESOURCES_DIR))
    }

    @Test
    fun `no directory is created when there are no compose resources`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResources(origins = emptyList(), outputDir = outputDir)

        assertTrue((outputDir / COMPOSE_RESOURCES_DIR).notExists())
    }

    @Test
    fun `resources of every fragment are packaged into the packaging directory`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResourcesHierarchy(
            fragments = listOf(
                fragmentOrigin("common", refines = emptySet(), "values/strings.xml"),
                fragmentOrigin("nonAndroid", refines = setOf("common"), "drawable/sailing.svg"),
                fragmentOrigin("jvm", refines = setOf("nonAndroid", "common"), "files/platform-text.txt"),
            ),
            outputDir = outputDir,
            packagingDir = PACKAGING_DIR,
        )

        assertEquals(
            listOf("drawable/sailing.svg", "files/platform-text.txt", "values/strings.xml"),
            filesIn(outputDir / PACKAGING_DIR),
        )
    }

    /**
     * This is the whole point of the refinement hierarchy: `ios/composeResources/foo` overrides
     * `common/composeResources/foo`, whether `ios` refines `common` directly or not.
     */
    @Test
    fun `a fragment overrides the fragments it refines`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResourcesHierarchy(
            fragments = listOf(
                fragmentOrigin("common", refines = emptySet(), "files/platform-text.txt", "values/strings.xml"),
                fragmentOrigin("ios", refines = setOf("apple", "native", "common"), "files/platform-text.txt"),
            ),
            outputDir = outputDir,
            packagingDir = PACKAGING_DIR,
        )

        assertEquals(
            listOf("files/platform-text.txt", "values/strings.xml"),
            filesIn(outputDir / PACKAGING_DIR),
        )
        assertEquals("ios/files/platform-text.txt", (outputDir / PACKAGING_DIR / "files/platform-text.txt").readText())
        assertEquals("common/values/strings.xml", (outputDir / PACKAGING_DIR / "values/strings.xml").readText())
    }

    /**
     * Only the refinement relation decides which fragment wins: neither the order the fragments are given in, nor
     * the number of fragments between them and the one they override.
     */
    @Test
    fun `the winning fragment does not depend on the order of the fragments`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResourcesHierarchy(
            // the most specific fragment comes first
            fragments = listOf(
                fragmentOrigin("ios", refines = setOf("apple", "native", "common"), "files/platform-text.txt"),
                fragmentOrigin("common", refines = emptySet(), "files/platform-text.txt"),
            ),
            outputDir = outputDir,
            packagingDir = PACKAGING_DIR,
        )

        assertEquals("ios/files/platform-text.txt", (outputDir / PACKAGING_DIR / "files/platform-text.txt").readText())
    }

    /**
     * A fragment that refines every other provider resolves the conflict between them, whether they refine each
     * other or not: `jvmAndAndroid` and `nonAndroid` are independent, but `jvm` refines both.
     */
    @Test
    fun `a fragment refining every other provider overrides all of them`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResourcesHierarchy(
            fragments = listOf(
                fragmentOrigin("jvmAndAndroid", refines = setOf("common"), "files/platform-text.txt"),
                fragmentOrigin("nonAndroid", refines = setOf("common"), "files/platform-text.txt"),
                fragmentOrigin(
                    "jvm",
                    refines = setOf("jvmAndAndroid", "nonAndroid", "common"),
                    "files/platform-text.txt",
                ),
            ),
            outputDir = outputDir,
            packagingDir = PACKAGING_DIR,
        )

        assertEquals("jvm/files/platform-text.txt", (outputDir / PACKAGING_DIR / "files/platform-text.txt").readText())
    }

    /**
     * Fragments that do not refine each other are independent, so neither of them can override the other one: the
     * build has to fail rather than package one of them arbitrarily.
     */
    @Test
    fun `a file provided by fragments that do not refine each other is reported`() = runTestWithMdc {
        val error = assertFailsWith<UserReadableError> {
            packageComposeResourcesHierarchy(
                fragments = listOf(
                    fragmentOrigin("common", refines = emptySet(), "values/strings.xml"),
                    fragmentOrigin("jvmAndAndroid", refines = setOf("common"), "files/platform-text.txt"),
                    fragmentOrigin("nonAndroid", refines = setOf("common"), "files/platform-text.txt"),
                ),
                outputDir = outputDir(),
                packagingDir = PACKAGING_DIR,
            )
        }

        // the path is reported the way it is packaged into the application, that is with its packaging directory
        assertContains(error.message, "${PACKAGING_DIR}files/platform-text.txt")
        assertContains(error.message, "fragment 'jvmAndAndroid'")
        assertContains(error.message, "fragment 'nonAndroid'")
        // the file that only 'common' provides is not in conflict with anything
        assertFalse(error.message.contains("values/strings.xml"))
    }

    /**
     * Refining some of the other providers is not enough: a fragment only wins when it refines all of them.
     */
    @Test
    fun `a file provided by a fragment refining only some of the other providers is reported`() = runTestWithMdc {
        val error = assertFailsWith<UserReadableError> {
            packageComposeResourcesHierarchy(
                fragments = listOf(
                    fragmentOrigin("jvmAndAndroid", refines = setOf("common"), "files/platform-text.txt"),
                    fragmentOrigin("nonAndroid", refines = setOf("common"), "files/platform-text.txt"),
                    fragmentOrigin("jvm", refines = setOf("nonAndroid", "common"), "files/platform-text.txt"),
                ),
                outputDir = outputDir(),
                packagingDir = PACKAGING_DIR,
            )
        }

        assertContains(error.message, "${PACKAGING_DIR}files/platform-text.txt")
        assertContains(error.message, "fragment 'jvm'")
        assertContains(error.message, "fragment 'jvmAndAndroid'")
        assertContains(error.message, "fragment 'nonAndroid'")
    }

    @Test
    fun `no directory is created when no fragment has compose resources`() = runTestWithMdc {
        val outputDir = outputDir()

        packageComposeResourcesHierarchy(fragments = emptyList(), outputDir = outputDir, packagingDir = PACKAGING_DIR)

        assertTrue((outputDir / COMPOSE_RESOURCES_DIR).notExists())
    }

    private fun outputDir(): Path = uniqueTempDir("output")

    private fun uniqueTempDir(directoryPrefix: String): Path =
        (tempDir / "$directoryPrefix-${UUID.randomUUID().toString().take(12)}").createDirectories()

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
     * The root of a merged origin, that is, an extracted KMP resources archive or the merged resources of a module,
     * holding the given [files] in its Compose resources directory.
     */
    private fun composeResourcesRootWith(vararg files: String): Path {
        // the directory is not named after the origin: a description may quote the name of an archive or of a module
        val rootDir = uniqueTempDir("root").createDirectories()
        (rootDir / COMPOSE_RESOURCES_DIR).createDirectories()
        files.forEach { (rootDir / COMPOSE_RESOURCES_DIR / it).createParentDirectories().writeText(it) }
        return rootDir
    }

    /**
     * The prepared Compose resources of the fragment named [fragmentName], which refines the fragments named
     * [refines] (directly or not), holding the given [files] (paths relative to its packaging directory). Each file
     * holds a content of its own so that the tests can tell which fragment a packaged file comes from.
     */
    private fun fragmentOrigin(
        fragmentName: String,
        refines: Set<String>,
        vararg files: String,
    ): FragmentComposeResources {
        val preparedDir = (uniqueTempDir("prepared") / PACKAGING_DIR).createDirectories()
        files.forEach { (preparedDir / it).createParentDirectories().writeText("$fragmentName/$it") }
        return FragmentComposeResources.of(
            fragmentName = fragmentName,
            refinedFragments = refines,
            preparedDir = preparedDir,
        ) ?: error("The prepared directory of the fragment $fragmentName should provide an origin")
    }

    /**
     * The files packaged into [dir], as paths relative to it.
     */
    private fun filesIn(dir: Path): List<String> = dir.walk()
        .filter { it.isRegularFile() }
        .map { it.relativeTo(dir).invariantSeparatorsPathString }
        .sorted()
        .toList()
}

private const val PACKAGING_DIR = "$COMPOSE_RESOURCES_DIR/app.generated.resources/"
