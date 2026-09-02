/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.compose

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertFileExists
import org.jetbrains.amper.cli.test.utils.assertGradleMetadataEquals
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.MacOnly
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.PathWalkOption
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Tag("cli-test-group-compose")
class ComposeResourcesTest : CliTestBase() {

    /**
     * A local Maven repository dedicated to the current test, so that the publications of these tests don't pollute
     * the real one, nor interfere with each other ([tempRoot] is specific to each test).
     */
    private val customM2LocalRepository: Path by lazy {
        (tempRoot / "m2Local" / "repository").createDirectories()
    }

    @Test
    fun `compose resources demo build (android)`() = runSlowTest {
        val taskName = ":app-android:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "task", taskName,
            configureAndroidHome = true,
        )

        // Compose resources are packaged as Android assets: the ones of this project are passed to AGP inside the AAR
        // built by the Kotlin Toolchain, while the ones of external libraries are merged by AGP itself from the AARs
        // it gets on the runtime classpath (this is why they need no KMP resources archive on Android).
        val apk = result.getTaskOutputPath(taskName)
            .walk(PathWalkOption.BREADTH_FIRST)
            .firstOrNull { it.extension == "apk" }
            ?: fail("No APK is found in the output of the '$taskName' task")
        val assets = ZipFile(apk.toFile()).use { apkZip ->
            apkZip.entries().asSequence().map { it.name }.filter { it.startsWith("assets/") }.toList()
        }
        assertContains(assets, "assets/composeResources/com.example.gen/files/platform-text.txt")
        assertContains(
            assets,
            "assets/composeResources/com.mohamedrejeb.calf.calf_cupertino_icons.generated.resources/font/sf_symbols.ttf",
        )
    }

    @Test
    fun `compose resources demo build and run (jvm)`() = runSlowTest {
        runCli(
            projectDir = testProject("compose-resources-demo"),
            "build", "--platform=jvm",
        )
        runCli(
            projectDir = testProject("compose-resources-demo"),
            "test", "--platform=jvm",
            assertEmptyStdErr = false,  // on some platforms/machines, the UI part may issue warnings to stderr
        )
    }

    @Test
    fun `compose resources custom res class name build`() = runSlowTest {
        runCli(
            projectDir = testProject("compose-resources-custom-res-class"),
            "build",
        )
    }

    /**
     * No module of the project sets a `packageName`, so the resources of each of them must be isolated under a
     * directory derived from the identity the module is published under: the publishing group, if the module declares
     * one, and the module name, as the artifact ID is not set either.
     *
     * The two libraries publish under the same group, and both provide a resource file at the same path, so
     * dropping the module name from the derived package doesn't just change the layout: the libraries then generate
     * their `Res` class in the very same package, and linking the app fails on the duplicated declarations before the
     * resource conflict is even reported.
     */
    @Test
    fun `compose resources default package name build`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-default-package"),
            "build",
        )

        val composeResources = result.getTaskOutputPath(":app:buildWasmJsAppWasmJsDebug") / "composeResources"
        // The libraries publish under the same group, so only the module name keeps their resources apart.
        assertFileExists(composeResources / "org.example.lib_one.generated.resources" / "files" / "lib-text.txt")
        assertFileExists(composeResources / "org.example.lib_two.generated.resources" / "files" / "lib-text.txt")
        // The app module has no publishing settings, so its resources are isolated under its module name alone.
        assertFileExists(composeResources / "app.generated.resources" / "files" / "lib-text.txt")
    }

    @Test
    @MacOnly
    fun `compose resources demo build (ios)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "build", "--platform=iosSimulatorArm64",
            assertEmptyStdErr = false,  // xcodebuild prints a bunch of warnings (unrelated to resources) for now :(
        )

        // xcodebuild is run with SYMROOT pointing at the 'bin' directory of the build task's output
        val appBundle = result.getTaskOutputPath(":app-ios:buildIosAppIosSimulatorArm64Debug") /
                "bin" / "Debug-iphonesimulator" / "app-ios.app"
        val bundledResources = appBundle / "compose-resources" / "composeResources" / "com.example.gen"
        assertFileExists(bundledResources / "drawable" / "land.webp")
        assertEquals("iOS refinement", (bundledResources / "files" / "refined-text.txt").readText())
    }

    @Test
    fun `dependency compose resources task is registered for compose-disabled ios app`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "show", "tasks",
        )

        assertContains(result.stdout, "task :app-ios:prepareComposeResourcesForIosIosArm64")
        assertContains(
            result.stdout,
            "task :app-ios:preBuildIosAppIosArm64Debug -> " +
                    ":app-ios:prepareComposeResourcesForIosIosArm64",
        )
    }

    @Test
    fun `compose resources IDE preparation`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "ide-integration", "prepare-compose-resources",
        )
        val sharedDir = result.buildDir / "generated" / "shared"
        assertTrue(sharedDir.exists())
        assertTrue((sharedDir / "common" / "preparedComposeResources" / "composeResources" / "com.example.gen").isDirectory())
        assertTrue((sharedDir / "common" / "src" / "compose" / "resources" / "accessors").isDirectory())
        assertTrue((sharedDir / "common" / "src" / "compose" / "resources" / "commonResClass").isDirectory())
    }

    @Test
    fun `compose resources merging (ios)`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "task", ":app-ios:prepareComposeResourcesForIosIosArm64"
        )
        val mergedDir = result.buildDir / "tasks" / "_app-ios_prepareComposeResourcesForIosIosArm64" / "merged"
        assertTrue(mergedDir.isDirectory())
        val generatedResourcesDir = mergedDir / "composeResources" / "com.example.gen"
        assertTrue(generatedResourcesDir.isDirectory())
        assertTrue((generatedResourcesDir / "drawable").isDirectory()) // Resources from common
        assertTrue((generatedResourcesDir / "files").isDirectory()) // Resources from ios

        // The resources of a fragment override the ones of the fragments it refines, whether it refines them
        // directly or not ('ios' refines 'common' through 'apple', 'native' and 'nonAndroid' here).
        assertEquals("iOS refinement", (generatedResourcesDir / "files" / "refined-text.txt").readText())

        // Only the refinement relation decides which fragment wins, not the distance between them: 'apple' and
        // 'common' are at the very same distance from the 'iosArm64' leaf fragment (through the
        // 'androidAndIosArm64' alias), but 'apple' does refine 'common'.
        assertEquals("Apple refinement", (generatedResourcesDir / "files" / "apple-text.txt").readText())
    }

    /**
     * A published KMP library must expose its Compose resources the way Compose Multiplatform libraries do, so that
     * both Amper and Gradle consumers can pick them up: as a KMP resources archive published in a dedicated Gradle
     * metadata variant, for every platform that doesn't pack its resources into the main artifact.
     */
    @Test
    fun `compose resources publication (kmp library)`(testInfo: TestInfo) = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-publication"),
            "task", ":library:prepareMavenPublishables",
        )

        val archive = result.getTaskOutputPath(":library:composeResourcesArchiveWasmJs") /
                "library-wasmjs-1.0.0-kotlin_resources.kotlin_resources.zip"
        assertTrue(archive.isRegularFile(), "The KMP resources archive is missing at $archive")
        assertEquals(
            listOf(
                "composeResources/com.example.lib.gen/files/common-text.txt",
                "composeResources/com.example.lib.gen/files/wasm-text.txt",
            ),
            archive.fileEntries(),
        )

        val publishablesDir = result.getTaskOutputPath(":library:prepareMavenPublishables")

        // The wasmJs publication carries the resource archive in a dedicated variant, the root one only redirects the
        // consumer to it, and the JVM one has no resources variant at all (it packs the resources into its jar).
        assertGradleMetadataEquals("module.json", publishablesDir / "library-1.0.0.module", testInfo)
        assertGradleMetadataEquals("wasmJs.module.json", publishablesDir / "library-wasmjs-1.0.0.module", testInfo)
        assertGradleMetadataEquals("jvm.module.json", publishablesDir / "library-jvm-1.0.0.module", testInfo)
    }

    /**
     * The native platforms are the other kind of platform that publishes its Compose resources in a dedicated variant.
     * Contrary to the JS/Wasm ones, they have no runtime variant, so their resources variant mirrors the API one (this
     * is what KGP does too), and it carries the native target attributes.
     */
    @Test
    @MacOnly
    fun `compose resources publication (native)`(testInfo: TestInfo) = runSlowTest {
        val result = runCli(
            projectDir = testProject("compose-resources-demo"),
            "task", ":shared:prepareMavenPublishables",
            configureAndroidHome = true,
            // The metadata compilation warns about the Compose artifacts that share a klib 'unique_name' with the
            // 'androidx.*' ones they were forked from. This is unrelated to resources, and pre-exists this test.
            assertEmptyStdErr = false,
        )

        val archive = result.getTaskOutputPath(":shared:composeResourcesArchiveIosArm64") /
                "shared-iosarm64-1.0.0-kotlin_resources.kotlin_resources.zip"
        assertTrue(archive.isRegularFile(), "The KMP resources archive is missing at $archive")
        // The archive holds the resources as they are merged for this platform, refinement included.
        assertContains(archive.fileEntries(), "composeResources/com.example.gen/files/refined-text.txt")
        assertContains(archive.fileEntries(), "composeResources/com.example.gen/files/apple-text.txt")

        val publishablesDir = result.getTaskOutputPath(":shared:prepareMavenPublishables")

        assertGradleMetadataEquals("module.json", publishablesDir / "shared-1.0.0.module", testInfo)
        assertGradleMetadataEquals("iosArm64.module.json", publishablesDir / "shared-iosarm64-1.0.0.module", testInfo)
    }

    /**
     * The KMP resources published by an Amper library must be consumable: a Compose app depending on such a library
     * must get the resources of that library packaged into its own output, just like for the Compose Multiplatform
     * libraries published by Gradle.
     */
    @Test
    fun `published compose resources are consumable (wasm js app)`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("compose-resources-publication"),
            mavenLocalRepository = customM2LocalRepository,
            "publish", "mavenLocal", "--module=library",
        )

        val result = runCliWithCustomM2(
            projectDir = testProject("compose-resources-publication-consumer"),
            mavenLocalRepository = customM2LocalRepository,
            "build", "--module=app",
        )

        val libResources = result.getTaskOutputPath(":app:buildWasmJsAppWasmJsDebug") /
                "composeResources" / "com.example.lib.gen" / "files"
        assertEquals("Common text", (libResources / "common-text.txt").readText().trim())
        assertEquals("Wasm text", (libResources / "wasm-text.txt").readText().trim())
    }

    /**
     * The KMP resources published for a native platform must be consumable as well: an iOS app depending on such a
     * library must get the resources of that library packaged next to its own ones, ready to go into the app bundle.
     */
    @Test
    @MacOnly
    fun `published compose resources are consumable (ios app)`() = runSlowTest {
        runCliWithCustomM2(
            projectDir = testProject("compose-resources-publication"),
            mavenLocalRepository = customM2LocalRepository,
            "publish", "mavenLocal", "--module=library-native",
            // same duplicate klib 'unique_name' warnings as in `compose resources publication (native)`
            assertEmptyStdErr = false,
        )

        val taskName = ":app-ios:prepareComposeResourcesForIosIosArm64"
        val result = runCliWithCustomM2(
            projectDir = testProject("compose-resources-publication-consumer"),
            mavenLocalRepository = customM2LocalRepository,
            "task", taskName,
        )

        val mergedDir = result.getTaskOutputPath(taskName) / "merged" / "composeResources"

        // The resources of the library come from the KMP resources archive published for this very platform, so they
        // include the ones the library only declares for iOS.
        val libResources = mergedDir / "com.example.libnative.gen" / "files"
        assertEquals("Common text", (libResources / "common-text.txt").readText().trim())
        assertEquals("iOS text", (libResources / "ios-text.txt").readText().trim())

        // The app's own resources are packaged alongside, under their own package name.
        val appResources = mergedDir / "com.example.appios.gen" / "files"
        assertEquals("App text", (appResources / "app-text.txt").readText().trim())
    }

    private fun Path.fileEntries(): List<String> = ZipFile(toFile()).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.sorted().toList()
    }
}
