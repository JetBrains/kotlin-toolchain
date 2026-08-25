/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.compose

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.MacOnly
import org.junit.jupiter.api.Tag
import java.util.zip.ZipFile
import kotlin.io.path.PathWalkOption
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.fail

@Tag("cli-test-group-compose")
class ComposeResourcesTest : CliTestBase() {

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

    @Test
    @MacOnly
    fun `compose resources demo build (ios)`() = runSlowTest {
        runCli(
            projectDir = testProject("compose-resources-demo"),
            "build", "--platform=iosSimulatorArm64",
            assertEmptyStdErr = false,  // xcodebuild prints a bunch of warnings (unrelated to resources) for now :(
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
    }
}