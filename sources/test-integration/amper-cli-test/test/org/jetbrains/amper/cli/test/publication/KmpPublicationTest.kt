/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.LinuxOnly
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.amper.test.TempDirTestClassExtension
import org.jetbrains.amper.test.WindowsOnly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

class KmpPublicationTest : CliTestBase() {

    @BeforeEach
    suspend fun publishKmpProjects() {
        if (!customM2LocalRepository.resolve("org").exists()) {
            if (mutex.isLocked) {
                println("Waiting until test project modules are published to the custom .m2 repository " +
                        "${customM2LocalRepository.absolutePathString()}...")
            }

            mutex.withLock {
                if (!customM2LocalRepository.resolve("org").exists()) {
                    println("Publishing test project modules to custom .m2 repository ${customM2LocalRepository.absolutePathString()}...")
                    val modulesToPublish = buildList {
                        add("edgeCase_jvmLib")
                        add("edgeCase_jvmPlusAndroid")
                        add("edgeCase_kmpSinglePlatform")
                        add("edgeCase_noSources")
                        add("edgeCase_wasmJsPlusWasmWasi")
                        add("library")
                        add("linuxWindowsShared")
                        add("nativePlatform")
                        add("nativeShared")

                        if (SystemInfo.CurrentHost.family == OsFamily.MacOs) {
                            add("libraryCinterop") // contains Mac/Linux specific cinterops => could be published from Mac only.
                            add("libraryNested") // contains Mac specific cinterops => could be published from Mac only.
                        }
                    }

                    runCliWithCustomM2(
                        projectDir = testProject("multiplatform-library-template-main"),
                        "publish", "mavenLocal", *modulesToPublish.map { "--module=$it" }.toTypedArray(),
                        configureAndroidHome = true,
                    )
                }
            }
        }
    }

    /**
     * This test checks that a pure jvm library is consumable from another jvm module.
     * The library is published WITH Gradle metadata, but WITHOUT allMetadata variant.
     */
    @Test
    fun `using published pure jvm library`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "build", "--module=jvmLibConsumer",
            configureAndroidHome = true,
        )
    }

    @Test
    fun `using published pure jvm library in test`() = runSlowTest {
        val r = runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--include-module", "jvmLibConsumer",
            "--platform", "jvm",
            configureAndroidHome = true,
        )

        // Asserts that test was actually run.
        r.assertStdoutContains("[         1 tests successful      ]")
        r.assertStdoutContains("[         0 tests failed          ]")
    }

    /**
     * This test checks that a single platform KMP jvm library is consumable from another jvm module.
     * The library is published WITH Gradle metadata and WITH allMetadata variant.
     */
    @Test
    fun `using published kmp jvm library`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "build", "--module=kmpJvmLibConsumer",
            configureAndroidHome = true,
        )
    }

    /**
     * This test checks that a published jvm plus android KMP library is consumable from another jvm plus android module
     */
    @Test
    fun `using published jvm plus android library`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "build", "--module=jvmPlusAndroidConsumer",
            configureAndroidHome = true,
        )
    }

    /**
     * This test checks that a published wasmJs plus wasmWasi KMP library is consumable from another wasmJs plus wasmWasi module
     */
    @Test
    fun `using published wasmJs plus wasmWasi library`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "build", "--module=wasmJsPlusWasmWasiConsumer",
        )
    }

    /**
     * This test checks that a published multiplatform native KMP library is consumable
     * from another KMP project.
     *
     * Note: This test calls leaf-platform compilation of the consuming KMP project only.
     */
    @Test
    fun `using published nativePlatform library for building leaf platforms`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "build", "--module=nativePlatformConsumer",
        )
    }

    /**
     * This test checks that a published source-less library is consumable from another KMP project.
     *
     * Such a library has no artifact for its native platforms (there is nothing to compile, hence no klib), so the
     * corresponding Gradle metadata variants have no file. The consumer must still be able to resolve the
     * publication and to use the dependencies exported by the library (see KTC-5652).
     */
    @Test
    fun `using published source-less library`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "build", "--module=noSourcesConsumer",
        )
    }

    /**
     * This test checks that a published multiplatform native KMP library is consumable
     * from another KMP project.
     *
     * Note: This test calls metadata compilation of the consuming KMP project shared fragments only.
     */
    @Test
    fun `using published nativePlatform library for shared fragments metadata compilation`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "task",
            ":nativePlatformConsumer:assembleMetadata",
        )
    }

    /**
     * This test checks that a symbol from a commonized OS platform used in a dependency ('nativePlatform') and
     * exposed as a public API is accessible from a comsuming library
     * (it is used in a test called on 'nativePlatformConsumer')
     */
    @Test
    @WindowsOnly
    fun `using published library in nativePlatform test on Windows`() = runSlowTest {
        val r = runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--include-module", "nativePlatformConsumer", "--platform", "mingwX64",
            "--include-test",
            "org.jetbrains.kotlintoolchain.kmp.sample.consumer.NativePlatformMingwX64ConsumerTest.test getPosixPathMax()",
            configureAndroidHome = true,
        )

        // Asserts that test was actually run.
        r.assertStdoutContains("Passed test getPosixPathMax")
    }

    @Test
    @WindowsOnly
    fun `using published library in Windows leaf platform test`() = runSlowTest {
        val r = runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--include-module", "libraryConsumer", "--platform", "mingwX64",
            "--include-test",
            "org.jetbrains.kotlintoolchain.kmp.sample.consumer.LibraryWindowsConsumerTest.test 3rd element()",
            configureAndroidHome = true,
        )

        // Asserts that test was actually run.
        r.assertStdoutContains("Passed test 3rd element")
    }

    @Test
    @LinuxOnly
    fun `using published library in Linux leaf platform test`() = runSlowTest {
        val r = runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--include-module", "libraryConsumer", "--platform", "linuxX64",
            "--include-test",
            "org.jetbrains.kotlintoolchain.kmp.sample.consumer.LibraryLinuxConsumerTest.test 3rd element()",
            configureAndroidHome = true,
        )

        // Asserts that test was actually run.
        r.assertStdoutContains("Passed test 3rd element")
    }

    @Test
    @MacOnly
    fun `using published library in macos leaf platform test`() = runSlowTest {
        val r = runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--include-module", "libraryConsumer", "--platform", "macosArm64",
            "--include-test",
            "org.jetbrains.kotlintoolchain.kmp.sample.consumer.LibraryMacosConsumerTest.test 3rd element()",
            configureAndroidHome = true,
        )

        // Asserts that test was actually run.
        r.assertStdoutContains("Passed test 3rd element")
    }

    @Test
    fun `using published library for building libraryConsumer leaf platforms`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "build", "--module=libraryConsumer",
            configureAndroidHome = true,
        )
    }

    @Test
    @MacOnly
    fun `using published library for assembling all libraryConsumer metadata`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "task", ":libraryConsumer:assembleMetadata",
            configureAndroidHome = true,
        )
    }

    /**
     * This test checks that the published multiplatform native KMP library with cinterops is consumable
     * from another KMP project.
     */
    @Test
    @MacOnly
    fun `using published library for assembling all libraryCinteropConsumer metadata`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "task", ":libraryCinteropConsumer:assembleMetadata",
            configureAndroidHome = true,
        )
    }

    @Test
    @WindowsOnly
    fun `published library can not be used for libraryConsumer metadata compilation on non-Mac platform`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "task", ":libraryConsumer:assembleMetadata",
            configureAndroidHome = true,
        )
    }

    /**
     * This test checks that a published multiplatform native KMP library is consumable
     * from another KMP project.
     *
     * Note: This test calls metadata compilation of the consuming KMP project shared fragments only.
     */
    @Test
    @WindowsOnly
    fun `publication of apple fragments with cinterop is forbidden from Windows`() = runSlowTest {
        val r = runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-template-main"),
            "publish", "mavenLocal", "--module=libraryNested", "--transitive",
            configureAndroidHome = true,
            expectedExitCode = 1,
            assertEmptyStdErr = false
        )

        assertTrue(
            actual = r.stderr.contains("Key macos_arm64 is missing in the map.")
                    || r.stderr.contains("Key macos_x64 is missing in the map."),
            message = "Process stderr must contain an error for the Kotlin CLI call (PID ${r.pid}). " +
                    "Kotlin Toolchain STDERR:\n" + r.stderr,
        )
    }

    private suspend fun runCliWithCustomM2(
        projectDir: Path,
        vararg args: String,
        expectedExitCode: Int? = 0,
        assertEmptyStdErr: Boolean = true,
        configureAndroidHome: Boolean = false,
    ): AmperCliResult = runCli(
        projectDir,
        args = args,
        expectedExitCode,
        assertEmptyStdErr,
        amperJvmArgs = ["-Dmaven.repo.local=\"${customM2LocalRepository.absolutePathString()}\""],
        configureAndroidHome = configureAndroidHome
    )

    companion object {
        @JvmStatic
        @RegisterExtension
        private val tempDirTestClassExtension = TempDirTestClassExtension()

        private val customM2LocalRepository by lazy {
            tempDirTestClassExtension.path.resolve("m2Local").resolve("repository").createDirectories()
        }

        private val mutex = Mutex()
    }
}