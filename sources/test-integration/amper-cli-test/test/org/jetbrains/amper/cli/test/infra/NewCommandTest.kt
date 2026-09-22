/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.infra

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertContainsRelativeFiles
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStderrDoesNotContain
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the parameterized Compose Multiplatform flow of `kotlin new` / `kotlin init`
 * (`--target-platform`, `--project-id`, `--from-template`, `--overwrite`).
 */
@Tag("cli-test-group-infra")
class NewCommandTest : CliTestBase() {
    @TempDir
    lateinit var nonRepositoryTempDir: Path

    @Test
    fun `new generates a single-target Compose Multiplatform app in a new directory`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "single-target").createDirectories()
        val result = runCli(
            workDir, "new", "myapp", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStdoutContains("cd myapp && kotlin build")
        val projectRoot = workDir / "myapp"
        assertContains((projectRoot / "shared/src/App.kt").readText(), "package org.example.myapp")
        projectRoot.assertContainsRelativeFiles(
            ".gitattributes",
            ".gitignore",
            "README.md",
            "desktopApp/module.yaml",
            "desktopApp/resources/logback.xml",
            "desktopApp/src/main.kt",
            "kotlin",
            "kotlin.bat",
            "libs.versions.toml",
            "project.yaml",
            "shared/composeResources/drawable/compose-multiplatform.xml",
            "shared/module.yaml",
            "shared/src/App.kt",
            "shared/src/Greeting.kt",
            "shared/src/Platform.kt",
            "shared/src@jvm/Platform.jvm.kt",
            "shared/test/PlatformTest.kt",
            "shared/test@jvm/PlatformJvmTest.kt",
            ignoreRootGitDirectory = true,
        )
        assertFalse((projectRoot / "app").exists(), "app should not be generated without a server target")
        val readme = (projectRoot / "README.md").readText()
        assertContains(readme, "/desktopApp")
        assertFalse("/app/" in readme)
        assertFalse("/androidApp" in readme)
        assertFalse("/iosApp" in readme)
        assertFalse("/webApp" in readme)
        assertFalse("/server" in readme)
        runCli(projectRoot, "build", wrapperMode = WrapperMode.GlobalIntrinsicVersion)
    }

    @Test
    fun `new generates a Ktor server target`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "server-target").createDirectories()
        runCli(
            workDir, "new", "myapp", "--target-platform=server",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val projectRoot = workDir / "myapp"
        projectRoot.assertContainsRelativeFiles(
            ".gitattributes",
            ".gitignore",
            "README.md",
            "core/module.yaml",
            "core/src/GreetingUtil.kt",
            "kotlin",
            "kotlin.bat",
            "libs.versions.toml",
            "project.yaml",
            "server/module.yaml",
            "server/resources/logback.xml",
            "server/src/Application.kt",
            "server/test/ApplicationTest.kt",
            ignoreRootGitDirectory = true,
        )
        assertContains(
            (projectRoot / "project.yaml").readText().replace("\r\n", "\n"),
            "modules:\n  - core\n  - server\n",
        )
        assertContains((projectRoot / "core/module.yaml").readText(), "platforms: [jvm]")
        assertContains(
            (projectRoot / "server/module.yaml").readText(),
            "mainClass: org.example.myapp.server.ApplicationKt",
        )
        assertContains((projectRoot / "server/src/Application.kt").readText(), "package org.example.myapp.server")
        assertFalse((projectRoot / "shared").exists(), "shared should not be generated without a UI target")

        runCli(projectRoot, "test", wrapperMode = WrapperMode.GlobalIntrinsicVersion)
    }

    @Test
    fun `new prunes the shared module platforms and modules to the selected targets`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        runCli(
            workDir, "new", "myapp", "--target-platform=desktop", "--target-platform=web",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val projectRoot = workDir / "myapp"
        assertOnlyDesktopAndWebModulesAreListed(projectRoot)
        assertSharedModuleDeclaresOnlyDesktopAndWebPlatforms(projectRoot)
        assertUnselectedTargetModulesAndSourcesAreAbsent(projectRoot)
    }

    @Test
    fun `new substitutes the project id and name everywhere`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        runCli(
            workDir, "new", "myapp",
            "--target-platform=android", "--target-platform=ios", "--target-platform=desktop",
            "--target-platform=web", "--target-platform=server",
            "--project-id=com.acme.demo",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val projectRoot = workDir / "myapp"
        assertContains((projectRoot / "core/src/GreetingUtil.kt").readText(), "package com.acme.demo")
        assertContains((projectRoot / "server/src/Application.kt").readText(), "package com.acme.demo.server")
        assertContains((projectRoot / "app/shared/src/App.kt").readText(), "package com.acme.demo")
        assertContains((projectRoot / "app/androidApp/src/MainActivity.kt").readText(), "package com.acme.demo")
        assertContains((projectRoot / "app/androidApp/module.yaml").readText(), "namespace: com.acme.demo")
        assertContains((projectRoot / "app/androidApp/module.yaml").readText(), "applicationId: com.acme.demo")
        assertContains((projectRoot / "app/androidApp/res/values/strings.xml").readText(), ">myapp</string>")
        assertContains((projectRoot / "app/desktopApp/src/main.kt").readText(), "title = \"myapp\"")
        assertContains((projectRoot / "app/webApp/resources/index.html").readText(), "<title>myapp</title>")
        assertPlatformIdentifiers(projectRoot, "com.acme.demo", "myapp")
    }

    @ParameterizedTest
    @CsvSource(
        "My-App, null",
        "My-App, com.acme.explicit",
        "My_App, null",
        "My_App, com.acme.explicit",
        "My App, null",
        "My App, com.acme.explicit",
        nullValues = ["null"],
    )
    fun `display names do not leak into generated identifiers with derived or explicit ids`(
        projectName: String,
        explicitId: String?,
    ) = runSlowTest {
        val workDir = newEmptyProjectDir()
        val idArgs = if (explicitId == null) [] else ["--project-id=$explicitId"]
        runCli(
            workDir, "new", projectName,
            "--target-platform=android", "--target-platform=ios", "--target-platform=desktop",
            "--target-platform=web", "--target-platform=server",
            *idArgs.toTypedArray(),
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        assertPlatformIdentifiers(workDir / projectName, explicitId ?: "org.example.myapp", projectName)
    }

    @Test
    fun `new always generates sample test sources`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        runCli(
            workDir, "new", "myapp", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val projectRoot = workDir / "myapp"
        assertTrue((projectRoot / "shared/test/PlatformTest.kt").isRegularFile())
        assertTrue((projectRoot / "shared/test@jvm/PlatformJvmTest.kt").isRegularFile())
    }

    @Test
    fun `new initializes a git repository with a gitignore by default`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "default-init").createDirectories()
        runCli(workDir, "new", "myapp", "--target-platform=desktop", wrapperMode = WrapperMode.GlobalIntrinsicVersion)

        val projectRoot = workDir / "myapp"
        val head = projectRoot / ".git" / "HEAD"
        assertTrue(head.isRegularFile(), "a git repository should have been initialized")
        assertEquals("ref: refs/heads/main", head.readText().trim(), "the repository should start on main")
        assertTrue((projectRoot / "shared/test/PlatformTest.kt").exists(), "Tests should be included by default")
        assertContains((projectRoot / "shared/src/App.kt").readText(), "package org.example.myapp")
        assertUsesOnlyLineEnding(projectRoot / "kotlin.bat", "\r\n")
        assertUsesOnlyLineEnding(projectRoot / "shared/src/App.kt", System.lineSeparator())
        val gitignore = (projectRoot / ".gitignore").readText()
        assertContains(gitignore, "/build/")
        assertContains(gitignore, ".idea/")
        assertFalse(".DS_Store" in gitignore)
        assertContains(gitignore, "**/module.xcodeproj/*")
        assertContains(gitignore, "!**/module.xcodeproj/project.pbxproj")
    }

    @ParameterizedTest
    @ValueSource(strings = ["--target-platform=desktop", "--from-template=jvm-cli"])
    fun `new does not generate Git repository or files with no-git`(sourceOption: String) = runSlowTest {
        val workDir = (nonRepositoryTempDir / "no-git").createDirectories()
        runCli(
            workDir, "new", "myapp", sourceOption, "--no-git",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val projectRoot = workDir / "myapp"
        assertFalse((projectRoot / ".git").exists(), "a git repository should not have been initialized")
        assertFalse((projectRoot / ".gitignore").exists(), ".gitignore should not be generated")
        assertFalse((projectRoot / ".gitattributes").exists(), ".gitattributes should not be generated")
    }

    @Test
    fun `new skips nested Git initialization with a note`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "nested-init").createDirectories()
        runCli(
            workDir, "new", "outer", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val outerProject = workDir / "outer"
        assertTrue((outerProject / ".git").exists(), "the outer Git repository should have been initialized")
        val result = runCli(
            outerProject, "new", "nested", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        assertFalse((outerProject / "nested/.git").exists(), "a nested Git repository should not be initialized")
        result.assertStdoutContains("Skipped Git repository initialization")
        result.assertStdoutContains("already inside a Git work tree")
    }

    @Test
    fun `new warns and succeeds when Git initialization fails`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "failed-init").createDirectories()
        val projectRoot = (workDir / "myapp").createDirectories()
        (projectRoot / ".git").writeText("not a gitdir pointer")

        val result = runCli(
            workDir, "new", "myapp", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        assertTrue((projectRoot / "project.yaml").isRegularFile())
        result.assertStdoutContains("Could not initialize a Git repository")
        result.assertStdoutContains("you can run `git init` manually later")
    }

    @Test
    fun `new generates a gitignore alongside the git repository`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        runCli(
            workDir, "new", "myapp", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        val projectRoot = workDir / "myapp"
        assertTrue((projectRoot / ".gitignore").isRegularFile(), ".gitignore should be generated")
        val gitignore = (projectRoot / ".gitignore").readText()
        assertContains(gitignore, "/build/")
        assertContains(gitignore, ".idea/")
        assertFalse(".DS_Store" in gitignore)
        assertContains(gitignore, "**/module.xcodeproj/*")
        assertContains(gitignore, "!**/module.xcodeproj/project.pbxproj")
    }

    @Test
    fun `init generates a Compose Multiplatform app in the current directory`() = runSlowTest {
        val workDir = (nonRepositoryTempDir / "current-directory-init").createDirectories()
        runCli(
            workDir, "init", "--target-platform=desktop",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        workDir.assertContainsRelativeFiles(
            ".gitattributes",
            ".gitignore",
            "README.md",
            "desktopApp/module.yaml",
            "desktopApp/resources/logback.xml",
            "desktopApp/src/main.kt",
            "kotlin",
            "kotlin.bat",
            "libs.versions.toml",
            "project.yaml",
            "shared/composeResources/drawable/compose-multiplatform.xml",
            "shared/module.yaml",
            "shared/src/App.kt",
            "shared/src/Greeting.kt",
            "shared/src/Platform.kt",
            "shared/src@jvm/Platform.jvm.kt",
            "shared/test/PlatformTest.kt",
            "shared/test@jvm/PlatformJvmTest.kt",
            ignoreRootGitDirectory = true,
        )
        assertFalse((workDir / "app").exists(), "app should not be generated without a server target")
    }

    @Test
    fun `new fails without a target`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val r = runCli(
            workDir, "new", "myapp",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        r.assertStderrContains("Please specify at least one --target-platform")
    }

    @Test
    fun `new and init explain project id requirements in help`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        for (command in ["new", "init"]) {
            val result = runCli(workDir, command, "--help", wrapperMode = WrapperMode.GlobalIntrinsicVersion)
            result.assertStdoutContains("non-empty dot-separated segments")
            result.assertStdoutContains("start with a lowercase ASCII letter")
            result.assertStdoutContains("lowercase ASCII letters and digits")
            result.assertStdoutContains("Kotlin hard keywords")
            result.assertStdoutContains("Java reserved words or literals")
            result.assertStdoutContains("java or kotlin as the first segment")
            result.assertStdoutDoesNotContain("Maximum length")
            result.assertStdoutContains("org.example.project")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["init", "new"])
    fun `invalid explicit project id is reported before missing platforms`(command: String) = runSlowTest {
        val workDir = newEmptyProjectDir()
        val nameArgs = if (command == "new") ["myapp"] else []
        val result = runCli(
            workDir, command, *nameArgs.toTypedArray(), "--project-id=com.my_app",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStderrContains("--project-id: Invalid project id 'com.my_app'")
        result.assertStderrContains("See --help for --project-id requirements.")
        result.assertStderrDoesNotContain("Please specify at least one --target-platform")
        assertTrue(workDir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `new fails with invalid cross-platform ids before creating any files`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val expectedReasonByProjectId = mapOf(
            "1nvalid id" to "the package name must have at least 2 segments separated with a dot",
            "com.my_app" to "package name segments must contain only lowercase ASCII letters and digits",
            "myapp" to "the package name must have at least 2 segments separated with a dot",
            "com.example.when" to "'when' is a reserved keyword and cannot be used as a package name segment",
            "Com.example.app" to "package name segments must contain only lowercase ASCII letters and digits",
        )
        expectedReasonByProjectId.forEach { (key, value) ->
            val r = runCli(
                workDir, "new", "myapp", "--target-platform=desktop", "--project-id=$key",
                expectedExitCode = 1,
                assertEmptyStdErr = false,
                wrapperMode = WrapperMode.GlobalIntrinsicVersion,
            )
            r.assertStderrContains(
                "--project-id: Invalid project id '$key': $value. See --help for --project-id requirements.",
            )
            r.assertStderrDoesNotContain("Use at least two non-empty dot-separated segments")
            assertFalse((workDir / "myapp").exists(), "Invalid input must not create the output directory")
        }
    }

    @Test
    fun `new rejects invalid ids before overwrite handling`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val projectRoot = (workDir / "myapp").createDirectories()
        val sentinel = projectRoot / "project.yaml"
        sentinel.writeText("existing project")
        val overwriteArgumentVariants: List<List<String>> = [[], ["--overwrite"]]
        for (overwriteArgs in overwriteArgumentVariants) {
            val r = runCli(
                workDir, "new", "myapp", "--target-platform=desktop", "--project-id=com.my_app",
                *overwriteArgs.toTypedArray(),
                expectedExitCode = 1,
                assertEmptyStdErr = false,
                wrapperMode = WrapperMode.GlobalIntrinsicVersion,
            )
            r.assertStderrContains("--project-id: Invalid project id 'com.my_app'")
            assertEquals("existing project", sentinel.readText())
            assertEquals([sentinel], projectRoot.listDirectoryEntries())
        }
    }

    @Test
    fun `new requires a project path`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val r = runCli(
            workDir, "new", "--target-platform=desktop",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        r.assertStderrContains("Please specify the project path")
        assertFalse((workDir / "project.yaml").exists())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `new rejects target-dir with or without a project name`(withProjectName: Boolean) = runSlowTest {
        val workDir = newEmptyProjectDir()
        val nameArgs = if (withProjectName) ["myapp"] else []
        val result = runCli(
            workDir, "new", *nameArgs.toTypedArray(), "--target-dir=actual-name", "--target-platform=desktop",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStderrContains("no such option --target-dir")
        assertFalse((workDir / "actual-name").exists())
        assertFalse((workDir / "myapp").exists())
    }

    @Test
    fun `new rejects a filesystem root without a project name`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val result = runCli(
            workDir, "new", checkNotNull(workDir.root).toString(), "--from-template=jvm-cli",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        result.assertStderrContains("Cannot determine a project name from target directory")
        result.assertStderrDoesNotContain("--target-dir")
        assertTrue(workDir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `only init advertises target-dir`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val initHelp = runCli(workDir, "init", "--help", wrapperMode = WrapperMode.GlobalIntrinsicVersion)
        val newHelp = runCli(workDir, "new", "--help", wrapperMode = WrapperMode.GlobalIntrinsicVersion)

        initHelp.assertStdoutContains("--target-dir")
        newHelp.assertStdoutDoesNotContain("--target-dir")
        newHelp.assertStdoutContains("project-path")
        newHelp.assertStdoutDoesNotContain("project-directory")
    }

    @Test
    fun `template mode warns about ignored options and does not need targets`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val result = runCli(
            workDir, "new", "myapp", "--from-template=jvm-cli", "--project-id=not a valid id",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        result.assertStdoutContains("--project-id is ignored when generating from a template.")
        assertTrue((workDir / "myapp/module.yaml").isRegularFile())
        assertTrue((workDir / "myapp/src/main.kt").isRegularFile())
    }

    @Test
    fun `new fails when both --target-platform and --from-template are given`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        runCli(
            workDir, "new", "myapp", "--target-platform=desktop", "--from-template=jvm-cli",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
    }

    @Test
    fun `new overwrites conflicting files with --overwrite`() = runSlowTest {
        val workDir = newEmptyProjectDir()
        val projectRoot = workDir / "myapp"
        val conflicting = (projectRoot / "project.yaml")
        conflicting.createParentDirectories()
        conflicting.writeText("stale content")

        runCli(
            workDir, "new", "myapp", "--target-platform=desktop", "--overwrite",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )

        assertFalse(conflicting.readText() == "stale content", "project.yaml should have been overwritten")
    }

    private fun assertUsesOnlyLineEnding(file: Path, lineEnding: String) {
        val content = file.readText()
        assertTrue(lineEnding in content, "${file.fileName} should contain line endings")
        assertEquals(content.replace("\r\n", "\n").replace("\n", lineEnding), content)
    }

    private fun assertOnlyDesktopAndWebModulesAreListed(projectRoot: Path) {
        assertContains(
            (projectRoot / "project.yaml").readText().replace("\r\n", "\n"),
            "modules:\n  - desktopApp\n  - shared\n  - webApp\n",
        )
    }

    private fun assertSharedModuleDeclaresOnlyDesktopAndWebPlatforms(projectRoot: Path) {
        assertContains((projectRoot / "shared/module.yaml").readText(), "platforms: [jvm, wasmJs]")
    }

    private fun assertUnselectedTargetModulesAndSourcesAreAbsent(projectRoot: Path) {
        assertFalse((projectRoot / "app").exists(), "app should not be generated without a server target")
        assertFalse((projectRoot / "androidApp").exists(), "androidApp should not be generated")
        assertFalse((projectRoot / "iosApp").exists(), "iosApp should not be generated")
        assertFalse((projectRoot / "shared/src@android").exists(), "shared/src@android should not be generated")
        assertFalse((projectRoot / "shared/src@ios").exists(), "shared/src@ios should not be generated")
    }

    private fun assertPlatformIdentifiers(projectRoot: Path, projectId: String, projectName: String) {
        assertContains((projectRoot / "core/src/GreetingUtil.kt").readText(), "package $projectId")
        assertContains((projectRoot / "server/src/Application.kt").readText(), "package $projectId.server")
        assertContains((projectRoot / "server/src/Application.kt").readText(), "import $projectId.sayHello")
        assertContains((projectRoot / "app/shared/src/App.kt").readText(), "package $projectId")
        assertContains((projectRoot / "app/shared/src/App.kt").readText(), "import $projectId.resources.Res")
        assertContains((projectRoot / "app/shared/module.yaml").readText(), "packageName: $projectId.resources")
        assertContains((projectRoot / "app/androidApp/module.yaml").readText(), "namespace: $projectId")
        assertContains((projectRoot / "app/androidApp/module.yaml").readText(), "applicationId: $projectId")
        val xcodeProject = (projectRoot / "app/iosApp/module.xcodeproj/project.pbxproj").readText()
        val bundleIds = xcodeProject.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("PRODUCT_BUNDLE_IDENTIFIER =") }
            .toList()
        assertEquals(List(2) { "PRODUCT_BUNDLE_IDENTIFIER = \"$projectId\";" }, bundleIds)
        assertContains((projectRoot / "app/androidApp/res/values/strings.xml").readText(), ">$projectName</string>")
        assertContains((projectRoot / "app/desktopApp/src/main.kt").readText(), "title = \"$projectName\"")
        assertContains((projectRoot / "app/webApp/resources/index.html").readText(), "<title>$projectName</title>")
    }
}
