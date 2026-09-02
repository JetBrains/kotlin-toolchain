/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.infra

import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.assertStdoutDoesNotContain
import org.jetbrains.amper.cli.test.utils.assertWarnings
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.LocalAmperPublication
import org.jetbrains.amper.test.TempDirExtension
import org.jetbrains.amper.wrapper.AmperWrapperData
import org.jetbrains.amper.wrapper.AmperWrappers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectory
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

class GlobalWrapperTest : CliTestBase() {

    @RegisterExtension
    private val tempDirExtension = TempDirExtension()
    private val tempDir: Path
        get() = tempDirExtension.path

    // NOTE: We parse this host "dynamic" version because we don't want to hardcode any dev versions here
    // TODO: Hardcode a released version once one with the launcher.sh compatibility is released.
    private lateinit var hostWrapperInfo: AmperWrapperData

    @BeforeEach
    fun setUp() {
        hostWrapperInfo = AmperWrapperData.parseFromProjectRoot(Dirs.amperCheckoutRoot)
            ?: error("Couldn't parse wrapper info from the Amper project")

        check(hostWrapperInfo.version != AmperBuild.mavenVersion) {
            "host: ${hostWrapperInfo.version} must not be equal to ${AmperBuild.mavenVersion}"
        }
    }

    @Disabled // FIXME AMPER-5342 restore this test once we migrate the project to the new wrappers
    @Test
    fun `global wrapper properly detects local version at root`() = runSlowTest {
        AmperWrappers.generate(
            targetDir = tempDir,
            amperVersion = hostWrapperInfo.version,
            amperDistTgzSha256 = hostWrapperInfo.sha256,
        )
        tempDir.resolve("project.yaml").writeText("modules: [ lib ]")
        tempDir.resolve("lib/module.yaml").createParentDirectories().writeText("product: jvm/lib")

        val result = runCli(
            projectDir = tempDir,
            "--version",
            wrapperMode = WrapperMode.Global,
        )

        result.assertStdoutContains("JetBrains Amper version ${hostWrapperInfo.version}")
        result.assertStdoutDoesNotContain(AmperBuild.mavenVersion)
    }

    @Test
    fun `force use intrinsic version`() = runSlowTest {
        AmperWrappers.generate(
            targetDir = tempDir,
            amperVersion = hostWrapperInfo.version,
            amperDistTgzSha256 = hostWrapperInfo.sha256,
        )
        tempDir.resolve("project.yaml").writeText("modules: []")

        val result = runCli(
            projectDir = tempDir,
            "--version",
            wrapperMode = WrapperMode.GlobalIntrinsicVersion,
        )
        result.assertStdoutContains("Kotlin Toolchain version ${AmperBuild.mavenVersion}")
        result.assertStdoutDoesNotContain(hostWrapperInfo.version)
    }

    @Disabled // FIXME AMPER-5342 restore this test once we migrate the project to the new wrappers
    @Test
    fun `global wrapper properly detects local version at nested dir`() = runSlowTest {
        AmperWrappers.generate(
            targetDir = tempDir,
            amperVersion = hostWrapperInfo.version,
            amperDistTgzSha256 = hostWrapperInfo.sha256,
        )
        tempDir.resolve("project.yaml").writeText("modules: [ lib ]")
        tempDir.resolve("lib/module.yaml").createParentDirectories().writeText("product: jvm/lib")

        val result = runCli(
            projectDir = tempDir / "lib",
            "--version",
            wrapperMode = WrapperMode.Global,
        )

        result.assertStdoutContains("JetBrains Amper version ${hostWrapperInfo.version}")
        result.assertStdoutDoesNotContain(AmperBuild.mavenVersion)
    }

    @Test
    fun `global wrapper warns when no project or module is detected near local wrapper`() = runSlowTest {
        LocalAmperPublication.setupWrappersIn(tempDir)
        tempDir.resolve("project.yaml").writeText("")

        val nested = tempDir / "project"
        val (wrapperSh, wrapperBat) = AmperWrappers.generate(
            targetDir = nested.createDirectory(),
            amperVersion = hostWrapperInfo.version,
            amperDistTgzSha256 = hostWrapperInfo.sha256,
        )
        val activeScriptPath = if (OsFamily.current == OsFamily.Windows) wrapperBat else wrapperSh

        val result = runCli(
            projectDir = nested,
            "--version",
            wrapperMode = WrapperMode.Global,
            assertEmptyStdErr = false,
        )

        result.assertStderrContains("WARNING: Found wrapper script '${activeScriptPath!!.absolutePathString()}'," +
                " but no project.yaml or module.yaml near it. Skipping.")

        result.assertStdoutContains("Kotlin Toolchain version ${AmperBuild.mavenVersion}")
        result.assertStdoutDoesNotContain(hostWrapperInfo.version)
    }

    @Test
    fun `distribution warns when called from global wrapper of another version with --project-dir`() = runSlowTest {
        // This is a current limitation of the global wrapper: it doesn't recognize --project-dir option

        val projectDir = tempDir.resolve("project").createDirectory()
        AmperWrappers.generate(
            targetDir = projectDir,
            amperVersion = hostWrapperInfo.version,
            amperDistTgzSha256 = hostWrapperInfo.sha256,
        )

        projectDir.resolve("project.yaml").writeText("modules: [ lib ]")
        projectDir.resolve("lib/module.yaml").createParentDirectories().writeText("product: jvm/lib")

        val anotherDir = tempDir.resolve("another").createDirectory()
        LocalAmperPublication.setupWrappersIn(anotherDir)

        val result = runCli(
            projectDir = anotherDir,
            "show", "modules",
            "--project-dir", projectDir.absolutePathString(),
            assertEmptyStdErr = false,
        )
        result.assertWarnings(
            "Running Kotlin CLI version (${AmperBuild.mavenVersion}) is different from the project wrapper version (${hostWrapperInfo.version}). " +
                    "NOTE: If you are using the global wrapper, make sure you run it inside the project directory."
        )
    }
}
