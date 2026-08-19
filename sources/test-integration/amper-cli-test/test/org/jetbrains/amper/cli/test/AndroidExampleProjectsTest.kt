/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import com.android.tools.apk.analyzer.BinaryXmlParser
import com.google.devrel.gmscore.tools.apk.arsc.ArscBlamer
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk
import io.opentelemetry.api.common.AttributeKey
import org.gradle.tooling.internal.consumer.ConnectorServices
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.core.extract.extractZip
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.android.AndroidTools
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.file.Path
import kotlin.io.path.PathWalkOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val androidSdkLicenseCacheSpanNamePrefix = "inc: run: android-sdk-license-check:"
private val incrementalCacheStatusKey = AttributeKey.stringKey("status")

class AndroidExampleProjectsTest : AmperCliTestBase() {

    @Test
    fun `simple tests debug`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("android/simple"),
            "task", ":simple:testAndroidDebug",
            configureAndroidHome = true,
        )
        result.assertStdoutContains("1 tests successful")
    }

    /**
     * This test checks that compile-only AAR dependencies are correctly transformed and passed to the compiler
     */
    @Test
    fun `android compile only aar dependency`() = runSlowTest {
        runCli(
            projectDir = testProject("android/compile-only-aar-dependency"),
            "build",
            configureAndroidHome = true,
        )
    }

    @Test
    fun `simple tests release`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("android/simple"),
            "task", ":simple:testAndroidRelease",
            configureAndroidHome = true,
        )
        result.assertStdoutContains("1 tests successful")
    }

    @Test
    fun `apk contains dependencies`() = runSlowTest {
        val taskName = ":simple:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("android/simple"),
            "task", taskName,
            configureAndroidHome = true,
        )
        val apkPath = result.getArtifactPath(taskName)
        assertClassContainsInApk("Lcom/google/common/collect/Synchronized\$SynchronizedBiMap;", apkPath)
    }

    @Test
    fun `AAR libs jars are available on the Android compile classpath`() = runSlowTest {
        val taskName = ":aar-libs-only:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("android/aar-libs-only"),
            "task", taskName,
            configureAndroidHome = true,
        )
        val apkPath = result.getArtifactPath(taskName)
        assertClassContainsInApk("Lcom/netease/nrtc/engine/rawapi/RtcConfig;", apkPath)
    }

    @Test
    fun `appcompat compiles successfully and contains dependencies`() = runSlowTest {
        val taskName = ":appcompat:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("android/appcompat"),
            "task",
            taskName,
            configureAndroidHome = true,
        )
        val apkPath = result.getArtifactPath(taskName)
        assertClassContainsInApk("Landroidx/appcompat/app/AppCompatActivity;", apkPath)
    }

    @Test
    fun `it's possible to use AppCompat theme from appcompat library in AndroidManifest`() = runSlowTest {
        val taskName = ":appcompat:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("android/appcompat"),
            "task",
            taskName,
            configureAndroidHome = true,
        )
        val apkPath = result.getArtifactPath(taskName)
        val extractedApkPath = apkPath.parent.resolve("extractedApk")
        extractZip(apkPath, extractedApkPath, false)
        val themeReference = getThemeReferenceFromAndroidManifest(extractedApkPath)
        assertThemeContainsInResources(extractedApkPath / "resources.arsc", themeReference)
    }

    @Test
    fun `should fail when license is not accepted`() = runSlowTest {
        val androidSdkHome = (Dirs.tempDir / "empty-android-sdk").also { it.createDirectories() }
        val result = runCli(
            projectDir = testProject("android/simple"),
            "build",
            configureAndroidHome = false,
            environment = mapOf("ANDROID_HOME" to androidSdkHome.absolutePathString()),
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        val sdkManagerPath = androidSdkHome / "cmdline-tools/22.0/bin/sdkmanager"

        // The missing license should be the one from the cmdline-tools, which is the only thing that was installed in
        // this empty SDK home. Since we install the latest version, we sometimes get the regular license and sometimes
        // the preview. That's why we need this 2-choice assertion.
        val expectedError1 = unacceptedLicenseMessage(sdkManagerPath, "android-sdk-license")
        val expectedError2 = unacceptedLicenseMessage(sdkManagerPath, "android-sdk-preview-license")
        if ("preview" in result.stderr) {
            assertContains(result.stderr, expectedError2)
        } else {
            assertContains(result.stderr, expectedError1)
        }
    }

    @Test
    @ResourceLock("android-sdk-license-cache-traces")
    fun `SDK license check cache is hit on a repeated check`() = runSlowTest {
        val projectDir = testProject("android/simple")
        val androidTools = AndroidTools.getOrInstallForTests()
        val androidSdkPath = createMinimalAndroidSdkForLicenseCacheTest(androidTools.androidSdkHome)
        val environment = androidEnvironment(androidTools, androidSdkPath)
        try {
            val coldCheck = runAndroidSdkLicenseCheck(projectDir, environment)
            assertEquals(
                "requires-building",
                coldCheck.telemetrySpans
                    .single { it.name.startsWith(androidSdkLicenseCacheSpanNamePrefix) }
                    .attributes[incrementalCacheStatusKey],
            )

            val warmCheck = runAndroidSdkLicenseCheck(projectDir, environment)
            assertEquals(
                "up-to-date",
                warmCheck.telemetrySpans
                    .single { it.name.startsWith(androidSdkLicenseCacheSpanNamePrefix) }
                    .attributes[incrementalCacheStatusKey],
            )
        } finally {
            androidSdkPath.deleteRecursively()
        }
    }

    @Test
    @ResourceLock("android-sdk-license-cache-traces")
    fun `SDK license check cache is invalidated when a package manifest is added or removed`() = runSlowTest {
        val projectDir = testProject("android/simple")
        val androidTools = AndroidTools.getOrInstallForTests()
        val androidSdkPath = createMinimalAndroidSdkForLicenseCacheTest(androidTools.androidSdkHome)
        val environment = androidEnvironment(androidTools, androidSdkPath)
        try {
            val coldCheck = runAndroidSdkLicenseCheck(projectDir, environment)
            assertEquals(
                "requires-building",
                coldCheck.telemetrySpans
                    .single { it.name.startsWith(androidSdkLicenseCacheSpanNamePrefix) }
                    .attributes[incrementalCacheStatusKey],
            )

            val extraPackageDir = androidSdkPath / "build-tools" / "amper-license-cache-test"
            val sourcePackageManifest = androidSdkPath / "cmdline-tools" / "22.0" / "package.xml"
            try {
                sourcePackageManifest.copyTo((extraPackageDir / "package.xml").createParentDirectories())
                val checkAfterAddingPackage = runAndroidSdkLicenseCheck(projectDir, environment)
                assertEquals(
                    "requires-building",
                    checkAfterAddingPackage.telemetrySpans
                        .single { it.name.startsWith(androidSdkLicenseCacheSpanNamePrefix) }
                        .attributes[incrementalCacheStatusKey],
                )

                val unchangedCheck = runAndroidSdkLicenseCheck(projectDir, environment)
                assertEquals(
                    "up-to-date",
                    unchangedCheck.telemetrySpans
                        .single { it.name.startsWith(androidSdkLicenseCacheSpanNamePrefix) }
                        .attributes[incrementalCacheStatusKey],
                )
            } finally {
                extraPackageDir.deleteRecursively()
            }

            val checkAfterRemovingPackage = runAndroidSdkLicenseCheck(projectDir, environment)
            assertEquals(
                "requires-building",
                checkAfterRemovingPackage.telemetrySpans
                    .single { it.name.startsWith(androidSdkLicenseCacheSpanNamePrefix) }
                    .attributes[incrementalCacheStatusKey],
            )
        } finally {
            androidSdkPath.deleteRecursively()
        }
    }

    private suspend fun runAndroidSdkLicenseCheck(
        projectDir: Path,
        environment: Map<String, String>,
    ): AmperCliResult = runCli(
        projectDir = projectDir,
        "task", ":simple:checkAndroidSdkLicenseAndroid",
        configureAndroidHome = false,
        environment = environment,
    )

    private fun createMinimalAndroidSdkForLicenseCacheTest(sourceAndroidSdkPath: Path): Path {
        val androidSdkPath = tempRoot / "android-sdk-license-cache-test"

        // The license check only needs the command-line tools package manifest and the accepted-license files.
        // Copying these to a private SDK keeps this test from mutating the persistent SDK shared by concurrent tests.
        val sourcePackageManifest = sourceAndroidSdkPath / "cmdline-tools" / "22.0" / "package.xml"
        sourcePackageManifest.copyTo(
            (androidSdkPath / "cmdline-tools" / "22.0" / "package.xml").createParentDirectories(),
        )
        (sourceAndroidSdkPath / "licenses").copyToRecursively(
            target = androidSdkPath / "licenses",
            followLinks = false,
        )
        return androidSdkPath
    }

    private fun androidEnvironment(androidTools: AndroidTools, androidSdkPath: Path): Map<String, String> =
        androidTools.environment() + mapOf(
            "ANDROID_HOME" to androidSdkPath.absolutePathString(),
            "ANDROID_SDK_ROOT" to androidSdkPath.absolutePathString(),
        )

    private fun unacceptedLicenseMessage(sdkManagerPath: Path, licenseName: String) = """
        Task ':simple:checkAndroidSdkLicenseAndroid' failed: Some licenses have not been accepted in the Android SDK:
         - $licenseName
        Run "$sdkManagerPath --licenses" to review and accept them
    """.trimIndent()

    @Test
    fun `bundle without signing enabled has no signature`() = runSlowTest {
        val taskName = ":simple:bundleAndroid"
        val result = runCli(
            projectDir = testProject("android/simple"),
            "task",
            taskName,
            configureAndroidHome = true,
        )
        val bundlePath = result.getArtifactPath(taskName, "aab")
        assertFileWithExtensionDoesNotContainInBundle("RSA", bundlePath)
    }

    @Test
    fun `bundle with signing enabled and properties file has signature`() = runSlowTest {
        val taskName = ":signed:bundleAndroid"
        val result = runCli(
            projectDir = testProject("android/signed"),
            "task",
            taskName,
            configureAndroidHome = true,
        )
        val bundlePath = result.getArtifactPath(taskName, "aab")
        assertFileContainsInBundle("ALIAS.RSA", bundlePath)
    }

    @Test
    fun `task graph is correct for downloading and installing android sdk components`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("android/simple"),
            "show", "tasks",
            configureAndroidHome = true,
        )
        // debug
        result.assertStdoutContains("task :simple:buildAndroidDebug -> :simple:runtimeClasspathAndroid")
        result.assertStdoutContains("task :simple:compileAndroidDebug -> :simple:installPlatformAndroid, :simple:transformDependenciesAndroid, :simple:resolveDependenciesAndroid, :simple:prepareAndroidDebug")
        result.assertStdoutContains("task :simple:prepareAndroidDebug -> :simple:installBuildToolsAndroid, :simple:installPlatformToolsAndroid, :simple:installPlatformAndroid, :simple:resolveDependenciesAndroid")
        result.assertStdoutContains("task :simple:runAndroidDebug -> :simple:installSystemImageAndroid, :simple:installEmulatorAndroid, :simple:buildAndroidDebug")
        // release
        result.assertStdoutContains("task :simple:buildAndroidRelease -> :simple:runtimeClasspathAndroid")
        result.assertStdoutContains("task :simple:compileAndroidRelease -> :simple:installPlatformAndroid, :simple:transformDependenciesAndroid, :simple:resolveDependenciesAndroid, :simple:prepareAndroidRelease")
        result.assertStdoutContains("task :simple:prepareAndroidRelease -> :simple:installBuildToolsAndroid, :simple:installPlatformToolsAndroid, :simple:installPlatformAndroid, :simple:resolveDependenciesAndroid")
        result.assertStdoutContains("task :simple:runAndroidRelease -> :simple:installSystemImageAndroid, :simple:installEmulatorAndroid, :simple:buildAndroidRelease")

        // transform dependencies
        // main
        result.assertStdoutContains("task :simple:transformDependenciesAndroid -> :simple:resolveDependenciesAndroid")
        // test
        result.assertStdoutContains("task :simple:transformDependenciesAndroidTest -> :simple:resolveDependenciesAndroidTest")

        // to accept android sdk license, we need cmdline tools
        result.assertStdoutContains("task :simple:checkAndroidSdkLicenseAndroid -> :simple:installCmdlineToolsAndroid")

        // Android sdk components can be installed separately, we need to check android sdk licenses every time.
        // Since scheduling ensures that only one instance of a task executed during the build, checking android sdk
        // license will be performed only once
        result.assertStdoutContains("task :simple:installBuildToolsAndroid -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installBuildToolsAndroidTest -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installEmulatorAndroid -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installEmulatorAndroidTest -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installPlatformAndroid -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installPlatformAndroidTest -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installPlatformToolsAndroid -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installPlatformToolsAndroidTest -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installSystemImageAndroid -> :simple:checkAndroidSdkLicenseAndroid")
        result.assertStdoutContains("task :simple:installSystemImageAndroidTest -> :simple:checkAndroidSdkLicenseAndroid")
    }

    @Test
    fun `package command produce aab bundle`() = runSlowTest {
        val taskName = ":signed:bundleAndroid"
        val result = runCli(
            projectDir = testProject("android/signed"),
            "package",
            configureAndroidHome = true,
        )
        val bundlePath = result.getArtifactPath(taskName, "aab")
        assertFileContainsInBundle("ALIAS.RSA", bundlePath)
    }

    @Test
    fun `mockable jar unit tests`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("android/mockable-jar"),
            "test",
            configureAndroidHome = true,
        )
        result.assertStdoutContains("5 tests successful")
    }

    @Test
    fun `mockable jar unit tests in multi-module setup`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("android/multi-module-mockable-jar"),
            "test",
            configureAndroidHome = true,
        )
        result.assertStdoutContains("5 tests successful")
    }

    @Test
    fun `apk contains jniLibs`() = runSlowTest {
        val taskName = ":jni-libs:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("android/jni-libs"),
            "task", taskName,
            configureAndroidHome = true,
        )
        val apkPath = result.getArtifactPath(taskName)
        val extractedApkPath = apkPath.parent.resolve("extractedApk")
        extractZip(apkPath, extractedApkPath, false)

        // AGP packages jniLibs/<abi>/libfoo.so into lib/<abi>/libfoo.so in the APK
        assertTrue(
            (extractedApkPath / "lib" / "arm64-v8a" / "libtest.so").exists(),
            "Expected lib/arm64-v8a/libtest.so in APK",
        )
        assertTrue(
            (extractedApkPath / "lib" / "x86_64" / "libtest.so").exists(),
            "Expected lib/x86_64/libtest.so in APK",
        )
    }

    @Test
    fun `robolectric unit tests`() = runSlowTest {
        val result = runCli(
            projectDir = testProject("android/robolectric"),
            "test",
            configureAndroidHome = true,
        )
        result.assertStdoutContains("2 tests successful")
    }

    @Test
    fun `Android resources are regenerated on changes`() = runSlowTest {
        val taskName = ":simple:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("android/simple"),
            "task", taskName,
            configureAndroidHome = true,
        )

        val stringsFile = result.projectDir / "res" / "values" / "strings.xml"
        stringsFile.writeText(stringsFile.readText().replace("custom_string", "new_string"))

        // Should fail because source usage is not renamed
        runCli(
            projectDir = result.projectDir,
            "task", taskName,
            configureAndroidHome = true,
            expectedExitCode = 1,
            assertEmptyStdErr = false,
        )

        val sourceFile = result.projectDir / "src" / "com" / "jetbrains" / "sample" / "app" / "MainActivity.kt"
        sourceFile.writeText(sourceFile.readText().replace("custom_string", "new_string"))

        // Should succeed
        runCli(
            projectDir = result.projectDir,
            "task", taskName,
            configureAndroidHome = true,
        )
    }


    @AfterTest
    fun tearDown() {
        ConnectorServices.reset()
    }

    private fun assertThemeContainsInResources(resourcesPath: Path, themeReference: Int) {
        val res = BinaryResourceFile((resourcesPath).readBytes())
        val chunk = res.chunks[0] as ResourceTableChunk
        val blamer = ArscBlamer(chunk)
        blamer.blame()
        val a = BinaryResourceIdentifier.create(themeReference)
        assertTrue(blamer.typeChunks.any { it.containsResource(a) })
    }

    private fun getThemeReferenceFromAndroidManifest(extractedApkPath: Path): Int {
        val decodedXml = BinaryXmlParser.decodeXml(
            "AndroidManifest.xml",
            (extractedApkPath / "AndroidManifest.xml").readBytes()
        )
        val decodedXmlString = decodedXml.decodeToString()
        val groups = "android:theme=\"@ref/(.*)\"".toRegex().find(decodedXmlString)
        val hex = groups?.groupValues?.get(1) ?: fail("There is no android theme reference in AndroidManifest.xml")
        val themeReference = hex.removePrefix("0x").toInt(16)
        return themeReference
    }

    private fun AmperCliResult.getArtifactPath(taskName: String, extension: String = "apk"): Path =
        getTaskOutputPath(taskName)
            .walk(PathWalkOption.BREADTH_FIRST)
            .firstOrNull { it.extension.equals(extension, ignoreCase = true) }
            ?: fail("artifact not found")

    private fun assertClassContainsInApk(dalvikFqn: String, apkPath: Path) {
        val extractedApkPath = apkPath.parent.resolve("extractedApk")
        extractZip(apkPath, extractedApkPath, false)
        val typesInDexes = extractedApkPath
            .walk()
            .filter { it.extension == "dex" }
            .flatMap { dex ->
                val dexFile = DexFileFactory.loadDexFile(dex.toFile(), Opcodes.forApi(34))
                dexFile.classes
            }
            .map { it.type }
        assertContains(typesInDexes.toList(), dalvikFqn)
    }

    private fun assertFileContainsInBundle(fileName: String, bundlePath: Path) {
        val extractedAabPath = bundlePath.parent.resolve("extractedBundle")
        extractZip(bundlePath, extractedAabPath, false)
        val files = extractedAabPath
            .walk()
            .map { it.name }
        assertContains(files.toList(), fileName)
    }

    private fun assertFileWithExtensionDoesNotContainInBundle(extension: String, bundlePath: Path) {
        val extractedApkPath = bundlePath.parent.resolve("extractedBundle")
        extractZip(bundlePath, extractedApkPath, false)
        val typesInDexes = extractedApkPath
            .walk()
            .map { it.extension }
            .filter { it.equals(extension, ignoreCase = true) }
        assertEquals(0, typesInDexes.toList().size)
    }
}
