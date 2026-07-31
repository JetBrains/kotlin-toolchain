/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.aomBuilder.readProjectModel
import org.jetbrains.amper.frontend.commonizedCinteropLibrariesRoot
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.problems.reporting.NoopProblemReporter
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.LinuxOnly
import org.jetbrains.amper.test.MacOnly
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals

class CinteropTest : AmperCliTestBase() {
    @Test
    @MacOnly
    fun `single app - run macosArm64`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/single-app-curl"),
            "run", "--platform=macosArm64",
        ).assertStdoutContains(EXAMPLE_COM_RESPONSE_TEXT)
    }

    @Test
    @MacOnly
    fun `lib + app - run macosArm64`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/lib-and-app-curl"),
            "run", "--module=app-mac", "--platform=macosArm64",
        ).assertStdoutContains(EXAMPLE_COM_RESPONSE_TEXT)
    }

    @Test
    @MacOnly
    fun `tests can use cinterop declarations`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/test-cinterop"),
            "test", "--platform=macosArm64",
        )
    }

    @Test
    @MacOnly
    fun `ide sync - commonize common cinterop for ios platforms`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("cinterop/ios-cinterop"),
            "ide-integration", "generate-klibs",
        )

        assertCinteropModel(
            result = result,
            expectedRepresentation = """
                module: ios-cinterop
                 commonized/ios-cinterop/(ios_arm64, ios_simulator_arm64)/
                  - foo
                  - bar
                  - custom
                 fragment: iosArm64 | generated/ios-cinterop/iosArm64/cinterop
                  - foo.klib
                  - bar.klib
                  - custom.klib
                 fragment: iosSimulatorArm64 | generated/ios-cinterop/iosSimulatorArm64/cinterop
                  - foo.klib
                  - bar.klib
                  - custom.klib
            """.trimIndent(),
        )
    }

    @Test
    @MacOnly
    fun `ide sync - commonize cinterop in non-intersecting fragments`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("cinterop/non-intersecting-fragments"),
            "ide-integration", "generate-klibs",
        )

        assertCinteropModel(
            result = result,
            expectedRepresentation = """
                module: non-intersecting-fragments
                 commonized/non-intersecting-fragments/(linux_arm64, linux_x64)/
                  - linux
                 commonized/non-intersecting-fragments/(ios_arm64, ios_simulator_arm64, ios_x64)/
                  - ios
                 fragment: iosArm64 | generated/non-intersecting-fragments/iosArm64/cinterop
                  - ios.klib
                 fragment: iosSimulatorArm64 | generated/non-intersecting-fragments/iosSimulatorArm64/cinterop
                  - ios.klib
                 fragment: iosX64 | generated/non-intersecting-fragments/iosX64/cinterop
                  - ios.klib
                 fragment: linuxArm64 | generated/non-intersecting-fragments/linuxArm64/cinterop
                  - linux.klib
                 fragment: linuxX64 | generated/non-intersecting-fragments/linuxX64/cinterop
                  - linux.klib
            """.trimIndent(),
        )
    }

    @Test
    @MacOnly
    fun `ide sync - no commonization for a single platform`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("cinterop/single-platform"),
            "ide-integration", "generate-klibs",
        )

        assertCinteropModel(
            result = result,
            expectedRepresentation = """
                module: single-platform
                 fragment: macosArm64 | generated/single-platform/macosArm64/cinterop
                  - custom.klib
            """.trimIndent(),
        )
    }

    @Test
    @MacOnly
    fun `ide sync - errors are ignored during cinterop klib gen`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("cinterop/mac-and-win"),
            "ide-integration", "generate-klibs",
            assertEmptyStdErr = false,
        )
        result.assertStdoutContains(
            "Warning: No libraries found for target mingw_x64. This target will be excluded from commonization.",
        )
        assertCinteropModel(
            result = result,
            expectedRepresentation = """
                module: mac-and-win
                 commonized/mac-and-win/(macos_arm64, mingw_x64)/
                  - libcurl
                 fragment: macosArm64 | generated/mac-and-win/macosArm64/cinterop
                  - libcurl.klib
                 fragment: mingwX64 | generated/mac-and-win/mingwX64/cinterop
                  - libcurl.klib.failed
            """.trimIndent(),
        )
    }

    @Test
    @MacOnly
    fun `ide sync - multi module project with cinterop (sync + test)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("cinterop/multi-module"),
            "ide-integration", "generate-klibs",
            assertEmptyStdErr = false,
        )
        assertCinteropModel(
            result = result,
            expectedRepresentation = """
                module: linux-cli
                 fragment: linuxArm64 | generated/linux-cli/linuxArm64/cinterop
                  - libcurl.klib.failed
                 fragment: linuxX64 | generated/linux-cli/linuxX64/cinterop
                  - libcurl.klib.failed
                module: macos-cli
                 fragment: macosArm64 | generated/macos-cli/macosArm64/cinterop
                  - libcurl.klib
                module: shared
                 commonized/shared/(linux_arm64, linux_x64, macos_arm64, macos_x64, mingw_x64)/
                  - custom
                 commonized/shared/(linux_arm64, linux_x64)/
                  - custom
                 commonized/shared/(macos_arm64, macos_x64)/
                  - custom
                 fragment: linuxArm64 | generated/shared/linuxArm64/cinterop
                  - custom.klib
                 fragment: linuxX64 | generated/shared/linuxX64/cinterop
                  - custom.klib
                 fragment: macosArm64 | generated/shared/macosArm64/cinterop
                  - custom.klib
                 fragment: macosX64 | generated/shared/macosX64/cinterop
                  - custom.klib
                 fragment: mingwX64 | generated/shared/mingwX64/cinterop
                  - custom.klib.failed
            """.trimIndent(),
        )

        runCli(
            projectDir = result.projectDir,
            "test", "-p", "macosArm64",
        )
    }

    @Test
    @MacOnly
    fun `build - errors are honored during cinterop klib gen`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("cinterop/mac-and-win"),
            "build",
            assertEmptyStdErr = false,
            expectedExitCode = 1,
        )
        result.assertStderrContains("cinterop processing failed for MINGW_X64, see the errors above")
    }

    @Test
    @LinuxOnly
    fun `lib + app - run linuxX64`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/lib-and-app-curl"),
            "run", "--module=app-linux",
        ).assertStdoutContains(EXAMPLE_COM_RESPONSE_TEXT)
    }

    @Test
    @MacOnly
    fun `via plugin`() = runSlowTest {
        runCli(
            projectDir = testProject("cinterop/cinterop-plugin"),
            "run", "--platform=macosArm64",
        )
    }

    private fun assertCinteropModel(
        result: AmperCliResult,
        expectedRepresentation: String,
    ) = assertEquals(
        actual = buildString {
            val model = readProjectModel(result.projectDir)
            for (module in model.modules.sortedBy { it.userReadableName }) {
                appendLine("module: ${module.userReadableName}")
                module.commonizedCinteropLibrariesRoot(result.buildDir)
                    .takeIf { it.isDirectory() }
                    ?.listDirectoryEntries()
                    ?.forEach { targetDir ->
                        appendLine(" ${targetDir.relativeTo(result.buildDir)}/")
                        targetDir.listDirectoryEntries().forEach {
                            appendLine("  - ${it.name}")
                        }
                    }
                for (fragment in module.fragments.sortedBy { it.name }) {
                    val dir = fragment.generatedCinteropKlibsDirPath(result.buildDir)
                        ?.takeIf { it.isDirectory() } ?: continue
                    appendLine(" fragment: ${fragment.name} | ${dir.relativeTo(result.buildDir)}")
                    for (path in dir.listDirectoryEntries()) {
                        appendLine("  - ${path.name}")
                    }
                }
            }
        }.trim(),
        expected = expectedRepresentation,
    )

    private fun readProjectModel(root: Path): Model = context(NoopProblemReporter) {
        val projectContext = AmperProjectContext.create(rootDir = root, buildDir = null)
            ?: error("Invalid project root: $root")
        projectContext.readProjectModel(pluginData = emptyList(), mavenPluginXmls = emptyList())
    }
}

private const val EXAMPLE_COM_RESPONSE_TEXT = "<title>Example Domain</title>"
