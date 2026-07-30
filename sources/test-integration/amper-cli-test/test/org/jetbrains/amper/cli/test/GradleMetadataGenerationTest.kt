/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import kotlinx.serialization.json.Json
import org.jetbrains.amper.cli.test.utils.assertFileContentEquals
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.Dirs
import org.jetbrains.gradle.module.metadata.format.Module
import org.junit.jupiter.api.TestInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.div
import kotlin.test.Test

// todo (AB) : [AMPER-721]
//  - add cinterop (direct and in refined fragment) to check that
//    commonized cinterop klibs are a part of KMP publication
class GradleMetadataGenerationTest : AmperCliTestBase() {

    val testGoldenFilesRoot: Path = Dirs.amperSourcesRoot.resolve("test-integration/amper-cli-test/testResources/gradleMetadata")

    @Test
    fun `libraryNested module`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":libraryNested:prepareMavenPublishables",
            configureAndroidHome = true,
        )

        val sanitizedGradleModuleMetadata = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_libraryNested_prepareMavenPublishables" / "libraryNested-1.0.0.module"
        )

        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.module.json"),
            sanitizedGradleModuleMetadata
        )

        val sanitizedLinuxX64GradleModuleMetadata = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_libraryNested_prepareMavenPublishables" / "libraryNested-linuxx64-1.0.0.module"
        )

        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.linuxX64.module.json"),
            sanitizedLinuxX64GradleModuleMetadata
        )
    }

    @Test
    fun `jvm plus android`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_jvmPlusAndroid:prepareMavenPublishables",
            configureAndroidHome = true,
        )

        val allMetadataGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_jvmPlusAndroid_prepareMavenPublishables" / "jvmPlusAndroid-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.module.json"),
            allMetadataGradleModuleMetadataSanitized
        )

        val jvmGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_jvmPlusAndroid_prepareMavenPublishables" / "jvmPlusAndroid-jvm-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.jvm.module.json"),
            jvmGradleModuleMetadataSanitized
        )

        val androidGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_jvmPlusAndroid_prepareMavenPublishables" / "jvmPlusAndroid-android-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.android.module.json"),
            androidGradleModuleMetadataSanitized
        )
    }

    @Test
    fun `wasmJs plus wasmWasi`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_wasmJsPlusWasmWasi:prepareMavenPublishables",
        )

        val allMetadataGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_wasmJsPlusWasmWasi_prepareMavenPublishables" / "wasmJsPlusWasmWasi-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.module.json"),
            allMetadataGradleModuleMetadataSanitized
        )

        val wasmJsGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_wasmJsPlusWasmWasi_prepareMavenPublishables" / "wasmJsPlusWasmWasi-wasmjs-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.wasmJs.module.json"),
            wasmJsGradleModuleMetadataSanitized
        )

        val wasmWasiGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_wasmJsPlusWasmWasi_prepareMavenPublishables" / "wasmJsPlusWasmWasi-wasmwasi-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.wasmWasi.module.json"),
            wasmWasiGradleModuleMetadataSanitized
        )
    }

    @Test
    fun `kmp jvm library`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_kmpSinglePlatform:prepareMavenPublishables",
        )

        val allMetadataGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_kmpSinglePlatform_prepareMavenPublishables" / "kmpSinglePlatform-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.module.json"),
            allMetadataGradleModuleMetadataSanitized
        )

        val jvmGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_kmpSinglePlatform_prepareMavenPublishables" / "kmpSinglePlatform-jvm-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.jvm.module.json"),
            jvmGradleModuleMetadataSanitized
        )
    }

    @Test
    fun `pure jvm library`(testInfo: TestInfo) = runSlowTest {
        runCli(projectDir = testProject("multiplatform-library-template-main"),
            "task",
            ":edgeCase_jvmLib:prepareMavenPublishables",
        )

        val allMetadataGradleModuleMetadataSanitized = getSanitizedGradleMetadataProducedByCli(
            tempRoot / "build" / "tasks" / "_edgeCase_jvmLib_prepareMavenPublishables" / "jvmLib-1.0.0.module"
        )
        assertFileContentEquals(
            testGoldenFilesRoot.resolve("${testInfo.testMethod.get().name.replace(" ", "_")}.module.json"),
            allMetadataGradleModuleMetadataSanitized
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