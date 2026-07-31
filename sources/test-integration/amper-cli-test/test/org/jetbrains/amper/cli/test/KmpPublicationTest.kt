/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.test.Ignore
import kotlin.test.Test

// todo (AB) : [AMPER-721]
//  - add cinterop (direct and in refined fragment) to check that
//    commonized cinterop klibs are added as an input to the native metadata compilation
class KmpPublicationTest : AmperCliTestBase() {

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
                        add("edgeCase_wasmJsPlusWasmWasi")
                        add("library")
                        add("linuxWindowsShared")
                        add("nativePlatform")
                        add("nativeShared")

                        if (SystemInfo.CurrentHost.family == OsFamily.MacOs) {
                            add("libraryNested") // contains Mac cinterops => should be published from Mac only.
                        }
                    }

                    runCliWithCustomM2(
                        projectDir = testProject("multiplatform-library-template-main"),
                        "publish", "mavenLocal", "--modules=${modulesToPublish.joinToString(",")}",
                        configureAndroidHome = true,
                    )
                }
            }
        }

        // todo (AB) :
        //  1. Create test that consumes project for every publication use case both platform-wise and metadata-compaltion-cases-wise.
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

    @Test
    @WindowsOnly
    fun `using published library in Windows leaf platform test`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--format", "teamcity", "--include-module", "libraryConsumer", "--platform", "mingwX64",
            "--include-test",
            "\"org.jetbrains.kotlintoolchain.kmp.sample.consumer.LibraryWindowsConsumerTest.test 3rd element()\" ",
            configureAndroidHome = true,
        )
    }

    @Test
    @LinuxOnly
    fun `using published library in Linux leaf platform test`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--format", "teamcity", "--include-module", "libraryConsumer", "--platform", "linuxX64",
            "--include-test",
            "\"org.jetbrains.kotlintoolchain.kmp.sample.consumer.LibraryLinuxConsumerTest.test 3rd element()\" ",
            configureAndroidHome = true,
        )
    }

    @Test
    @MacOnly
    fun `using published library in macos leaf platform test`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-consumer"),
            "test", "--format", "teamcity", "--include-module", "libraryConsumer", "--platform", "macosArm64",
            "--include-test",
            "\"org.jetbrains.kotlintoolchain.kmp.sample.consumer.LibraryMacosConsumerTest.test 3rd element()\" ",
            configureAndroidHome = true,
        )
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

    // todo (AB) :
    //  1. This test should check that metadata compilation fails with some non-cryptic error.
    //  2. Run it on Linux as well
    @Ignore
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
    // todo (AB) : This test should fail on Windows.
    //  It does work until cinterop metadata compilation is supported in publication.
    fun `publication of apple fragments with cinterop is forbidden from Windows`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("multiplatform-library-template-main"),
            "publish", "mavenLocal", "--modules=libraryNested",
            configureAndroidHome = true,
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


