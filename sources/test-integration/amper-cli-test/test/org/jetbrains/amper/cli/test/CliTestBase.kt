/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import org.jetbrains.amper.processes.runProcess
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.AmperCliWithWrapperTestBase
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.JavaHomeMode
import org.jetbrains.amper.test.LocalAmperPublication
import org.jetbrains.amper.test.TempDirExtension
import org.jetbrains.amper.test.android.AndroidTools
import org.jetbrains.amper.test.processes.TestReporterProcessOutputListener
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString

@Tag("slow")
abstract class CliTestBase : AmperCliWithWrapperTestBase() {
    @RegisterExtension
    private val tempDirExtension = TempDirExtension()

    protected val tempRoot: Path
        get() = tempDirExtension.path

    companion object {
        /**
         * A temp directory where we placed the wrapper scripts that will be used to run Amper.
         * We don't want to add them to the test projects themselves, because we don't want to pollute git.
         */
        private val tempWrappersDir: Path by lazy {
            Dirs.tempDir.resolve("local-cli-wrappers").createDirectories().also {
                LocalAmperPublication.setupWrappersIn(it)
            }
        }
    }

    /**
     * Creates a copy of the test project with the given [name], and returns the path to the copy.
     *
     * The original test projects are not expected to contain the Amper wrappers themselves, but
     * the wrappers are generated into the project copy unless [setupWrappers] is set to false.
     *
     * Note: the [runCli] function may use these project-local wrappers directly, or use a global
     * wrapper that will provision a version based on the local wrappers.
     */
    protected fun testProject(
        name: String,
        setupWrappers: Boolean = true,
    ): Path {
        return copyProjectToTempDir(Dirs.amperTestProjectsRoot.resolve(name)).also {
            if (setupWrappers) LocalAmperPublication.setupWrappersIn(it)
        }
    }

    /**
     * Creates a new temporary empty directory to use as a test project.
     * Files may be created by the test in this directory, as it will automatically be cleaned up after the test.
     *
     * @param setupWrappers whether to set up the Amper wrappers in the new directory. `false` by default.
     */
    protected fun newEmptyProjectDir(
        setupWrappers: Boolean = false,
    ): Path {
        return tempRoot.resolve("new").createDirectories().also {
            if (setupWrappers) LocalAmperPublication.setupWrappersIn(it)
        }
    }

    protected fun copyProjectToTempDir(projectRoot: Path): Path {
        val tempProjectDir = tempRoot / UUID.randomUUID().toString() / projectRoot.fileName
        tempProjectDir.createDirectories()
        projectRoot.copyToRecursively(target = tempProjectDir, overwrite = false, followLinks = true)
        return tempProjectDir
    }

    protected suspend fun runCli(
        projectDir: Path,
        vararg args: String,
        expectedExitCode: Int? = 0,
        assertEmptyStdErr: Boolean = true,
        modifyProjectBeforeRun: (projectDir: Path) -> Unit = {},
        stdin: ProcessInput = ProcessInput.Empty,
        outputListener: ProcessOutputListener = TestReporterProcessOutputListener("amper", testReporter),
        amperJvmArgs: List<String> = emptyList(),
        amperJavaHomeMode: JavaHomeMode = JavaHomeMode.ForceUnset,
        configureAndroidHome: Boolean = false,
        environment: Map<String, String> = emptyMap(),
        wrapperMode: WrapperMode = WrapperMode.Local,
        buildOutputRoot: Path = tempRoot.resolve("build"),
    ): AmperCliResult {
        println("Running Kotlin CLI with '${args.toList()}' on $projectDir")

        modifyProjectBeforeRun(projectDir)

        val kotlinWrapperPath = if (wrapperMode.isGlobal) {
            tempWrappersDir / scriptNameForCurrentOs
        } else {
            (projectDir / scriptNameForCurrentOs).also {
                check(it.exists()) { "Using project-local wrapper mode, but the wrapper is missing in $projectDir" }
            }
        }

        val effectiveArgs = buildList {
            add("--shared-cache-dir=${Dirs.userCacheRoot.absolutePathString()}")
            addAll(args)
        }

        val result = runAmper(
            workingDir = projectDir,
            args = effectiveArgs,
            environment = buildMap {
                if (configureAndroidHome) {
                    putAll(AndroidTools.prepareForTests().environment())
                }
                put("AMPER_BUILD_DIR", buildOutputRoot.pathString)
                put("AMPER_NO_GRADLE_DAEMON", "1")
                if (wrapperMode == WrapperMode.GlobalIntrinsicVersion) {
                    put("KOTLIN_CLI_WRAPPER_ALWAYS_USE_INTRINSIC_VERSION", "1")
                }
                putAll(environment)
            },
            bootstrapCacheDir = Dirs.userCacheRoot,
            expectedExitCode = expectedExitCode,
            assertEmptyStdErr = assertEmptyStdErr,
            stdin = stdin,
            amperJvmArgs = amperJvmArgs,
            amperJavaHomeMode = amperJavaHomeMode,
            customAmperScriptPath = kotlinWrapperPath,
            outputListener = outputListener,
        )

        testReporter.publishEntry("Kotlin CLI[${result.pid}] arguments", args.joinToString(" "))
        testReporter.publishEntry("Kotlin CLI[${result.pid}] working dir", projectDir.pathString)
        testReporter.publishEntry("Kotlin CLI[${result.pid}] exit code", result.exitCode.toString())
        val logsDir = result.logsDir
        if (logsDir != null) {
            testReporter.publishDirectory(logsDir)
        }

        return result
    }

    /**
     * Runs the CLI like [runCli] does, but using [mavenLocalRepository] as the local Maven repository, so that the
     * publications of the tests don't pollute the real one.
     *
     * It is useful for the tests that publish some stuff and check that consumer works well with that.
     */
    protected suspend fun runCliWithCustomM2(
        projectDir: Path,
        mavenLocalRepository: Path,
        vararg args: String,
        expectedExitCode: Int? = 0,
        assertEmptyStdErr: Boolean = true,
        configureAndroidHome: Boolean = false,
    ): AmperCliResult = runCli(
        projectDir = projectDir,
        args = args,
        expectedExitCode = expectedExitCode,
        assertEmptyStdErr = assertEmptyStdErr,
        amperJvmArgs = ["-Dmaven.repo.local=\"${mavenLocalRepository.absolutePathString()}\""],
        configureAndroidHome = configureAndroidHome,
    )

    protected suspend fun runXcodebuild(
        vararg buildArgs: String,
        workingDir: Path = tempRoot,
        action: String = "build",
    ): ProcessResult.WithOutputs = runProcess(
        workingDir = workingDir,
        command = listOf(
            "xcrun", "xcodebuild",
            *buildArgs,
            action,
        ),
        environment = baseEnvironmentForWrapper(),
        outputMode = ProcessOutputMode.listenAndCapture(TestReporterProcessOutputListener("xcodebuild", testReporter)),
    )

    protected enum class WrapperMode {
        /**
         * Call the local wrapper present in the project root.
         */
        Local,

        /**
         * Call a global wrapper (which is not in the project), and rely on version detection to use
         * the version of the wrapper that's in the project.
         * This is the normal behavior of the global wrapper.
         */
        Global,

        /**
         * Call a global wrapper (which is not in the project) in a special mode that makes it use its
         * own embedded version instead of detecting the version from the project-local wrapper.
         *
         * Note: this is not the normal behavior of the global wrapper, but it's convenient in some tests.
         */
        GlobalIntrinsicVersion,
    }

    private val WrapperMode.isGlobal: Boolean
        get() = when (this) {
            WrapperMode.Local -> false
            else -> true
        }
}
