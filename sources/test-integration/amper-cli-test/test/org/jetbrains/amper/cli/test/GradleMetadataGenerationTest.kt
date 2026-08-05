/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.test.utils.assertFileContentEquals
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.MacOnly
import org.jetbrains.gradle.module.metadata.format.Module
import org.junit.jupiter.api.TestInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.div
import kotlin.test.Test

class GradleMetadataGenerationTest : AmperCliTestBase() {

    val testGoldenFilesRoot: Path = Dirs.amperSourcesRoot.resolve("test-integration/amper-cli-test/testResources/gradleMetadata")

    @Test
    @MacOnly
    fun `libraryNested module`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":libraryNested:prepareMavenPublishables",
            configureAndroidHome = true,
        )

        assertMetadataFilesEquals("libraryNested-1.0.0.module", "module.json", testInfo)

        assertMetadataFilesEquals("libraryNested-linuxx64-1.0.0.module", "linuxX64.module.json", testInfo)
    }

    @Test
    fun `jvm plus android`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_jvmPlusAndroid:prepareMavenPublishables",
            configureAndroidHome = true,
        )

        assertMetadataFilesEquals("jvmPlusAndroid-1.0.0.module", "module.json", testInfo, "edgeCase_jvmPlusAndroid")

        assertMetadataFilesEquals("jvmPlusAndroid-jvm-1.0.0.module", "jvm.module.json", testInfo, "edgeCase_jvmPlusAndroid")

        assertMetadataFilesEquals("jvmPlusAndroid-android-1.0.0.module", "android.module.json", testInfo, "edgeCase_jvmPlusAndroid")
    }

    @Test
    @MacOnly
    fun `libraryCinterop module`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":libraryCinterop:prepareMavenPublishables",
            configureAndroidHome = true,
        )

        assertMetadataFilesEquals("libraryCinterop-1.0.0.module", "module.json", testInfo)

        assertMetadataFilesEquals("libraryCinterop-linuxx64-1.0.0.module", "linuxX64-module.json", testInfo)

        assertMetadataFilesEquals("libraryCinterop-macosarm64-1.0.0.module", "macosArm64-module.json", testInfo)
    }

    @Test
    fun `wasmJs plus wasmWasi`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_wasmJsPlusWasmWasi:prepareMavenPublishables",
        )

        assertMetadataFilesEquals("wasmJsPlusWasmWasi-1.0.0.module", "module.json", testInfo, "edgeCase_wasmJsPlusWasmWasi")

        assertMetadataFilesEquals("wasmJsPlusWasmWasi-wasmjs-1.0.0.module", "wasmJs.module.json", testInfo, "edgeCase_wasmJsPlusWasmWasi")

        assertMetadataFilesEquals("wasmJsPlusWasmWasi-wasmwasi-1.0.0.module", "wasmWasi.module.json", testInfo, "edgeCase_wasmJsPlusWasmWasi")
    }

    @Test
    fun `kmp jvm library`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_kmpSinglePlatform:prepareMavenPublishables",
        )

        assertMetadataFilesEquals("kmpSinglePlatform-1.0.0.module", "module.json", testInfo, "edgeCase_kmpSinglePlatform")

        assertMetadataFilesEquals("kmpSinglePlatform-jvm-1.0.0.module", "jvm.module.json", testInfo, "edgeCase_kmpSinglePlatform")
    }

    @Test
    fun `pure jvm library`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_jvmLib:prepareMavenPublishables",
        )

        assertMetadataFilesEquals("jvmLib-1.0.0.module", "module.json", testInfo, "edgeCase_jvmLib")
    }

    private fun assertMetadataFilesEquals(
        artifactName: String,
        expectedArtifactFileName: String,
        testInfo: TestInfo,
        moduleName: String = artifactName.substringBefore("-"),
    ) {
        val sanitizedLinuxX64GradleModuleMetadata = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_${moduleName}_prepareMavenPublishables" / artifactName
        )

        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.$expectedArtifactFileName"),
            sanitizedLinuxX64GradleModuleMetadata
        )
    }

    private fun getSanitizedGradleMetadataProducedByCli(
        gradleModuleMetadataFile: Path,
    ): Path {
        val gradleModuleMetadata = gradleModuleMetadataFile.readPGradleModuleMetadata()
        val sanitizedGradleModuleMetadata = gradleModuleMetadata.copy(
            variants = gradleModuleMetadata.variants.map {
                if (it.files.isNotEmpty()) {
                    it.copy(files = it.files.map {
                        it.copy(sha512 = "mocked", sha256 = "mocked", sha1 = "mocked", md5 = "mocked", size = -1)
                    })
                } else {
                    it
                }
            }
        ).serialize()

        val patchedGradleModuleMetadata = gradleModuleMetadataFile.parent.resolve("${gradleModuleMetadataFile.fileName}-patched")

        Files.writeString(patchedGradleModuleMetadata, sanitizedGradleModuleMetadata,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

        return patchedGradleModuleMetadata
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }


        fun Path.readPGradleModuleMetadata(): Module = json.decodeFromString(Files.readString(this))
        fun Module.serialize(): String = json.encodeToString(this)
    }
}