/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertFileContentEquals
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.Dirs
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Checks the `kotlin-tooling-metadata.json` file that is published next to the root artifact of a multiplatform
 * library (with the `kotlin-tooling-metadata` classifier and the `json` extension).
 *
 * Important: the golden files were derived from the files that a Gradle project with the same module configuration
 * produces (kept next to them with the `.original_gradle.json` suffix, generated from the equivalent modules of the
 * `multiplatform-library-template-main-gradle` test project with `./gradlew :<module>:buildKotlinToolingMetadata`).
 *
 * The fields describing the build system itself always differ because the Kotlin Toolchain is not Gradle + KGP, and
 * the order of the targets is different (ours is sorted to be reproducible). Everything else must stay in sync with
 * what KGP produces so that the consumers of this file keep working. The few remaining deviations are documented in
 * the tests below.
 */
@Tag("cli-test-group-publication")
class KotlinToolingMetadataTest : CliTestBase() {

    private val testGoldenFilesRoot: Path =
        Dirs.amperSourcesRoot.resolve("test-integration/amper-cli-test/testResources/kotlinToolingMetadata")

    @Test
    fun `kmp single platform`(testInfo: TestInfo) = runSlowTest {
        runCli(
            projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_kmpSinglePlatform:prepareMavenPublishables",
        )

        assertToolingMetadataEquals(testInfo, moduleName = "edgeCase_kmpSinglePlatform")
    }

    /**
     * Note: the Gradle reference file reports the Android target of the new AGP multiplatform library plugin
     * (`KotlinMultiplatformAndroidLibraryTargetImpl`) with no `extras` at all, while we report KGP's own
     * `KotlinAndroidTarget` along with the Java compatibility levels, as KGP does for a plain `androidTarget()`.
     */
    @Test
    fun `jvm plus android`(testInfo: TestInfo) = runSlowTest {
        runCli(
            projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_jvmPlusAndroid:prepareMavenPublishables",
            configureAndroidHome = true,
        )

        assertToolingMetadataEquals(testInfo, moduleName = "edgeCase_jvmPlusAndroid")
    }

    @Test
    fun `wasmJs plus wasmWasi`(testInfo: TestInfo) = runSlowTest {
        runCli(
            projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_wasmJsPlusWasmWasi:prepareMavenPublishables",
        )

        assertToolingMetadataEquals(testInfo, moduleName = "edgeCase_wasmJsPlusWasmWasi")
    }

    /**
     * Native targets are the only ones that report the Kotlin/Native target and version in the `extras` section.
     *
     * Note: KGP reports `KotlinNativeTargetWithHostTests` for the native targets that can run tests on their own host
     * (`linuxX64` and `mingwX64` here), which is a KGP implementation type that has no equivalent here, so we always
     * report the plain `KotlinNativeTarget`.
     */
    @Test
    fun `native only`(testInfo: TestInfo) = runSlowTest {
        runCli(
            projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":linuxWindowsShared:prepareMavenPublishables",
        )

        assertToolingMetadataEquals(testInfo, moduleName = "linuxWindowsShared")
    }

    /**
     * A multiplatform library mixing a JVM target with a native one.
     *
     * This module has no sources, so no klib is compiled for its native target. The Kotlin/Native ABI version can only
     * be read from a klib. The format requires it as part of the native `extras`, so the native target reports no
     * `extras` at all here (the Gradle reference file has them, because KGP reads that version from the plugin itself).
     */
    @Test
    fun `jvm plus native`(testInfo: TestInfo) = runSlowTest {
        runCli(
            projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_noSources:prepareMavenPublishables",
        )

        assertToolingMetadataEquals(testInfo, moduleName = "edgeCase_noSources")
    }

    /**
     * A plain `jvm/lib` is not published as a multiplatform library, so it gets no tooling metadata, just like a
     * Gradle project applying the Kotlin JVM plugin only.
     */
    @Test
    fun `jvm library has no tooling metadata`() = runSlowTest {
        runCli(
            projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_jvmLib:prepareMavenPublishables",
        )

        val toolingMetadata = toolingMetadataPath(moduleName = "edgeCase_jvmLib")
        assertFalse(toolingMetadata.exists(), "Unexpected tooling metadata file for a jvm/lib module: $toolingMetadata")
    }

    private fun assertToolingMetadataEquals(testInfo: TestInfo, moduleName: String) {
        val goldenFileName = "${testInfo.testMethod.get().name.replace(" ", "_")}.kotlin-tooling-metadata.json"
        assertFileContentEquals(
            testGoldenFilesRoot.resolve(goldenFileName),
            toolingMetadataPath(moduleName),
        )
    }

    private fun toolingMetadataPath(moduleName: String): Path =
        tempRoot / "build" / "tasks" / "_${moduleName}_prepareMavenPublishables" / "kotlin-tooling-metadata.json"
}
