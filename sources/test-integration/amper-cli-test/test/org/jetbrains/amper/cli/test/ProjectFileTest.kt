/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.assertWarnings
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.LocalAmperPublication
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.io.path.div
import kotlin.io.path.pathString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ProjectFileTest : AmperCliTestBase() {

    @Test
    fun modules() = runSlowTest {
        val r = runCli(testProject("simple-multiplatform-cli"), "show", "modules", "--format=plain")

        assertModulesList(r, listOf(
            "js-cli",
            "jvm-cli",
            "linux-cli",
            "macos-cli",
            "shared",
            "utils",
            "wasm-js-cli",
            "wasm-wasi-cli",
            "windows-cli",
        ))
    }

    @Test
    fun `single-module project under an unrelated project`() = runSlowTest {
        val resultNested = runCli(
            testProject("nested-project-root") / "nested-project",
            "show", "modules", "--format=plain",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        assertModulesList(resultNested, listOf("nested-project"))

        val resultRoot = runCli(testProject("nested-project-root"), "show", "modules", "--format=plain")
        assertModulesList(resultRoot, listOf("included-module"))
    }

    @Test
    fun `empty project file and no module file`() = runSlowTest {
        val projectRoot = testProject("project-root-no-modules")
        val result = runCli(projectRoot, "build")
        result.assertWarnings("Nothing to build")
        // The following warning doesn't show up in the logs, so we check for it separately in the stdout.
        result.assertStdoutContains("""
        |WARNING: Project has no modules: no root module file and no modules listed in the project file
        | ╰→ ${projectRoot.resolve("project.yaml")}
        """.trimMargin())
    }

    @Test
    fun `project including a deep module`() = runSlowTest {
        val result = runCli(testProject("project-root-deep-inclusion"), "show", "modules", "--format=plain")
        assertModulesList(result, listOf("deep-module"))
    }

    @Test
    fun `project with denormalized globs`() = runSlowTest {
        val result = runCli(testProject("project-root-denormalized-globs"), "show", "modules", "--format=plain")
        assertModulesList(result, listOf("deep", "deep2", "sub1", "sub2", "sub3", "sub4"))
    }

    @Test
    fun `project with both top-level and nested modules`() = runSlowTest {
        val result = runCli(testProject("top-level-and-nested-modules"), "show", "modules", "--format=plain")
        assertModulesList(result, listOf("deep-module", "top-level-and-nested-modules"))
    }

    @Test
    fun `project file with path errors`() = runSlowTest {
        val projectDir = testProject("project-file-with-errors", setupWrappers = false) / "project"
        LocalAmperPublication.setupWrappersIn(projectDir)
        val r = runCli(
            projectDir = projectDir,
            "show", "modules",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        val projectYaml = projectDir / "project.yaml"
        assertContains(r.stdout, """
            |    ╭─ WEAK WARNING: It is recommended to sort the `modules` list alphabetically. This reduces the chance of Git conflicts and makes it easier to visually locate a module in the list.
            |    │ → $projectYaml:2:3
            |    │
            |    │   ⌄⌄⌄⌄⌄⌄⌄
            |  2 │   - valid
            |  3 │   - ./does-not-exist
            |  4 │   - ./does/not/exist
            |  5 │   - not-a-dir
            |  6 │   - not-a-module
            |  7 │   - glob-with-no-matches-at-all/*
            |  8 │   - not-a-modul? # matches some dirs, but none with a module.yaml in it
            |  9 │   - broken[syntax
            | 10 │   - broken[z-a]syntax
            | 11 │   - broken[syntax/with/**
            | 12 │   - forbidden/**/recursive
            | 13 │   - ../jvm-default-compiler-settings # out of root
            | 14 │   - ./ # redundant root module
            |    │ ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stdout, """
            |    ╭─ WEAK WARNING: Glob pattern `glob-with-no-matches-at-all/*` doesn't match any Kotlin module directory under the project root
            |    │ → $projectYaml:7:5
            |    │
            |  7 │   - glob-with-no-matches-at-all/*
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stdout, """
            |    ╭─ WEAK WARNING: Glob pattern `not-a-modul?` doesn't match any Kotlin module directory under the project root
            |    │ → $projectYaml:8:5
            |    │
            |  8 │   - not-a-modul? # matches some dirs, but none with a module.yaml in it
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stdout, """
            |    ╭─ WEAK WARNING: The root module is included by default
            |    │ → $projectYaml:14:5
            |    │
            | 14 │   - ./ # redundant root module
            |    │     ⌃⌃
            |    ╰─""".trimMargin())

        assertContains(r.stderr, """
            |    ╭─ ERROR: Unresolved module path `./does-not-exist`
            |    │ → $projectYaml:3:5
            |    │
            |  3 │   - ./does-not-exist
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: Unresolved module path `./does/not/exist`
            |    │ → $projectYaml:4:5
            |    │
            |  4 │   - ./does/not/exist
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: `not-a-dir` is not a directory
            |    │ → $projectYaml:5:5
            |    │
            |  5 │   - not-a-dir
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: Directory `not-a-module` doesn't contain a Kotlin module file
            |    │ → $projectYaml:6:5
            |    │
            |  6 │   - not-a-module
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: Invalid glob pattern `broken[syntax`: Missing '] near index 12
            |    │ broken[syntax
            |    │             ^
            |    │
            |    │ → $projectYaml:9:5
            |    │
            |  9 │   - broken[syntax
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: Invalid glob pattern `broken[z-a]syntax`: Invalid range near index 7
            |    │ broken[z-a]syntax
            |    │        ^
            |    │
            |    │ → $projectYaml:10:5
            |    │
            | 10 │   - broken[z-a]syntax
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: Invalid glob pattern `broken[syntax/with/**`: Explicit 'name separator' in class near index 13
            |    │ broken[syntax/with/**
            |    │              ^
            |    │
            |    │ → $projectYaml:11:5
            |    │
            | 11 │   - broken[syntax/with/**
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: Unsupported `**` in module glob pattern `forbidden/**/recursive`. Use multiple single-level `*` segments instead to specify the depth exactly.
            |    │ → $projectYaml:12:5
            |    │
            | 12 │   - forbidden/**/recursive
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, """
            |    ╭─ ERROR: Directory `../jvm-default-compiler-settings` is not under the project root
            |    │ → $projectYaml:13:5
            |    │
            | 13 │   - ../jvm-default-compiler-settings # out of root
            |    │     ⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃⌃
            |    ╰─""".trimMargin())
        assertContains(r.stderr, "ERROR: Aborting because there were errors in the Kotlin project file, please see above")
    }

    @Test
    fun `invalid project root`() = runSlowTest {
        val explicitRoot = testProject("invalid-project-root")
        val r = runCli(
            projectDir = explicitRoot,
            "show", "modules", "--project-dir=${explicitRoot.pathString}",
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )
        val expected = "ERROR: The given path '$explicitRoot' is not a valid Kotlin project root " +
                "directory. Make sure you have a project file or a module file at the root of your Kotlin project."
        assertEquals(expected, r.stderr.trim())
    }

    @Test
    fun `project module list alphabetical order with special chars`() = runSlowTest {
        val explicitRoot = testProject("project-modules-sorted-alphabetically")
        val r = runCli(projectDir = explicitRoot, "show", "modules")
        r.assertStdoutDoesNotContain("It is recommended to sort the `modules` list alphabetically")
    }

    private fun assertModulesList(modulesCommandResult: AmperCliResult, expectedModules: List<String>) =
        assertEquals(expectedModules, modulesCommandResult.stdoutClean.trim().lines())
}
