/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.infra

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertContainsRelativeFiles
import org.jetbrains.amper.cli.test.utils.assertFileContentEquals
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStderrDoesNotContain
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.test.LocalAmperPublication
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("cli-test-group-infra")
class InitCommandTest : CliTestBase() {

    @Test
    fun `init generates a project from the given template`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        runCli(newRoot, "init", "--from-template=jvm-cli", wrapperMode = WrapperMode.GlobalIntrinsicVersion)

        newRoot.assertContainsRelativeFiles(
            ".gitattributes",
            ".gitignore",
            "kotlin",
            "kotlin.bat",
            "module.yaml",
            "src/World.kt",
            "src/main.kt",
            "test/WorldTest.kt",
        )
    }

    @Test
    fun `init generates a project into an existing directory with --target-dir`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        (newRoot / "foo").createDirectories()
        runCli(newRoot, "init", "--target-dir=foo", "--from-template=jvm-cli", wrapperMode = WrapperMode.GlobalIntrinsicVersion)

        (newRoot / "foo").assertContainsRelativeFiles(
            ".gitattributes",
            ".gitignore",
            "kotlin",
            "kotlin.bat",
            "module.yaml",
            "src/World.kt",
            "src/main.kt",
            "test/WorldTest.kt",
        )
    }

    @Test
    fun `init fails when the target directory has no project name`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        val fileSystemRoot = checkNotNull(newRoot.root)

        val result = runCli(
            newRoot, "init", "--target-dir=$fileSystemRoot",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStderrContains("Cannot determine a project name from target directory")
        result.assertStderrContains("Choose a named directory.")
    }

    @Test
    fun `in-place Compose init derives an id and prints the build command without cd`() = runSlowTest {
        val newRoot = newEmptyProjectDir()

        val result = runCli(
            newRoot, "init", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStdoutDoesNotContain("  cd ")
        result.assertStdoutContains("kotlin build")
        assertContains((newRoot / "shared/src/App.kt").readText(), "package org.example.projectnew")
    }

    @Test
    fun `out-of-place Compose init derives an id and prints quoted cd steps`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        (newRoot / "My App").createDirectories()

        val result = runCli(
            newRoot, "init", "--target-dir=My App", "--target-platform=android", "--target-platform=ios",
           
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStdoutContains(if (OsFamily.current.isWindows) "cd \"My App\"" else "cd 'My App'")
        val projectRoot = newRoot / "My App"
        assertContains((projectRoot / "shared/src/App.kt").readText(), "package org.example.myapp")
        assertContains((projectRoot / "androidApp/module.yaml").readText(), "applicationId: org.example.myapp")
        val xcodeProject = (projectRoot / "iosApp/module.xcodeproj/project.pbxproj").readText()
        val bundleIdSetting = "PRODUCT_BUNDLE_IDENTIFIER = \"org.example.myapp\";"
        assertEquals(2, Regex.fromLiteral(bundleIdSetting).findAll(xcodeProject).count())
    }

    @Test
    fun `Compose init validates project ids before overwrite handling`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        val targetRoot = (newRoot / "existing").createDirectories()
        val sentinel = targetRoot / "project.yaml"
        sentinel.writeText("existing project")

        val result = runCli(
            newRoot, "init", "--target-dir=existing", "--target-platform=desktop", "--project-id=com.my_app",
            "--overwrite",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStderrContains(
            "--project-id: Invalid project id 'com.my_app': " +
                    "package name segments must contain only lowercase ASCII letters and digits",
        )
        result.assertStderrContains("See --help for --project-id requirements.")
        result.assertStderrDoesNotContain("Use at least two non-empty dot-separated segments")
        assertEquals("existing project", sentinel.readText())
        assertEquals([sentinel], targetRoot.listDirectoryEntries())
    }

    @Test
    fun `init overwrites existing wrapper scripts`() = runSlowTest {
        val newRoot = newEmptyProjectDir()

        val bashWrapper = newRoot.resolve("kotlin")
        val batWrapper = newRoot.resolve("kotlin.bat")

        bashWrapper.writeText("w1")
        batWrapper.writeText("w2")

        runCli(newRoot, "init", "--from-template=jvm-cli", wrapperMode = WrapperMode.GlobalIntrinsicVersion, assertEmptyStdErr = false)

        assertTrue(batWrapper.readText().count { it == '\r' } > 10,
            "Windows wrapper must have \\r in line separators: $batWrapper")
        assertTrue(bashWrapper.readText().count { it == '\r' } == 0,
            "Unix wrapper must not have \\r in line separators: $bashWrapper")

        assertTrue("Unix wrapper must be executable: $bashWrapper") { bashWrapper.isExecutable() }

        assertFileContentEquals(LocalAmperPublication.wrapperSh, bashWrapper)
        assertFileContentEquals(LocalAmperPublication.wrapperBat, batWrapper)
    }

    @Test
    fun `init doesn't replace existing files - single`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        val existingModuleFile = newRoot.resolve("module.yaml")
        existingModuleFile.writeText("some text in module.yaml")

        val r = runCli(
            newRoot, "init", "--from-template=jvm-cli",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        val expectedStderr = """
            ERROR: The following conflicts must be resolved before generating the project:

            Files that would be overwritten by the template:
              module.yaml

            Either move, rename, or delete them, or re-run the command with --overwrite to replace
            the conflicting files and directories.
        """.trimIndent()
        r.assertStderrContains(expectedStderr)

        newRoot.assertContainsRelativeFiles("module.yaml")
        assertEquals("some text in module.yaml", existingModuleFile.readText())
    }

    @Test
    fun `init fails with clear error when a top-level directory needed by the template exists as a file`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        // The jvm-cli template expects src/ to be a directory, but it exists as a regular file here
        newRoot.resolve("src").writeText("not a directory")

        val r = runCli(
            newRoot, "init", "--from-template=jvm-cli",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        val expectedStderr = """
            ERROR: The following conflicts must be resolved before generating the project:

            Paths that exist as files but are needed as directories:
              src
            
            Either move, rename, or delete them, or re-run the command with --overwrite to replace
            the conflicting files and directories.
        """.trimIndent()
        r.assertStderrContains(expectedStderr)
    }

    @Test
    fun `init fails with clear error when a nested directory needed by the template exists as a file`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        // The spring-boot-kotlin template has src/org/jetbrains/amper/spring/Main.kt, so src/org/jetbrains must be a
        // directory. Create its parent as a real directory and the nested path as a regular file.
        newRoot.resolve("src/org").createDirectories()
        newRoot.resolve("src/org/jetbrains").writeText("not a directory")

        val r = runCli(
            newRoot, "init", "--from-template=spring-boot-kotlin",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        val expectedStderr = """
            ERROR: The following conflicts must be resolved before generating the project:

            Paths that exist as files but are needed as directories:
              src/org/jetbrains
            
            Either move, rename, or delete them, or re-run the command with --overwrite to replace
            the conflicting files and directories.
        """.trimIndent()
        r.assertStderrContains(expectedStderr)
    }

    @Test
    fun `init doesn't replace existing files - multiple`() = runSlowTest {
        val newRoot = newEmptyProjectDir()
        val existingModuleFile = newRoot.resolve("module.yaml")
        val existingSourceFile = newRoot.resolve("src/main.kt").createParentDirectories()
        existingModuleFile.writeText("some text in module.yaml")
        existingSourceFile.writeText("some text in main.kt")

        val r = runCli(
            newRoot, "init", "--from-template=jvm-cli",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        val expectedStderr = """
            ERROR: The following conflicts must be resolved before generating the project:

            Files that would be overwritten by the template:
              module.yaml
              src/main.kt
            
            Either move, rename, or delete them, or re-run the command with --overwrite to replace
            the conflicting files and directories.
        """.trimIndent()
        r.assertStderrContains(expectedStderr)

        newRoot.assertContainsRelativeFiles("module.yaml", "src/main.kt")
        assertEquals("some text in module.yaml", existingModuleFile.readText())
        assertEquals("some text in main.kt", existingSourceFile.readText())
    }
}