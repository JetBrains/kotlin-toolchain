/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.android

import com.android.tools.apk.analyzer.BinaryXmlParser
import com.google.devrel.gmscore.tools.apk.arsc.ArscBlamer
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk
import org.gradle.tooling.internal.consumer.ConnectorServices
import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.getTaskOutputPath
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.core.extract.extractZip
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.Dirs
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.collections.iterator
import kotlin.io.path.PathWalkOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
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

@Tag("cli-test-group-android")
class AndroidProjectsTest : CliTestBase() {

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

        val sdkManagerPath = androidSdkHome / "cmdline-tools/latest/bin/sdkmanager"
        if ("preview" in result.stderr) {
            // As we fetch the latest cmdline-tools, we might get preview license for it.
            val expectedError = unacceptedLicenseMessage(
                sdkManagerPath,
                mapOf(
                    "android-sdk-license" to ["build-tools;37.0.0", "platform-tools", "platforms;android-37.0"],
                    "android-sdk-preview-license" to ["cmdline-tools;latest"]
                )
            )
            result.assertStderrContains(expectedError)
        } else {
            val expectedError = unacceptedLicenseMessage(
                sdkManagerPath,
                mapOf("android-sdk-license" to ["build-tools;37.0.0", "cmdline-tools;latest", "platform-tools", "platforms;android-37.0"])
            )
            result.assertStderrContains(expectedError)
        }
    }

    private fun unacceptedLicenseMessage(sdkManagerPath: Path, licenseMap: Map<String, List<String>>) = buildString {
        appendLine("ERROR: Some licenses have not been accepted in the Android SDK:")
        for ([licenseKey, packages] in licenseMap) {
            appendLine(" - `$licenseKey` (required by: `$packages`)")
        }
        appendLine()
        append("Run `$sdkManagerPath --licenses` to review and accept them")
    }

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
        result.assertStdoutContains("task :simple:prepareAndroidDebug -> :simple:installBuildToolsAndroid, installCmdlineTools, installPlatformTools, :simple:installPlatformAndroid, :simple:resolveDependenciesAndroid")
        result.assertStdoutContains("task :simple:runAndroidDebug -> :simple:installSystemImageAndroid, installEmulator, :simple:buildAndroidDebug")
        // release
        result.assertStdoutContains("task :simple:buildAndroidRelease -> :simple:runtimeClasspathAndroid")
        result.assertStdoutContains("task :simple:compileAndroidRelease -> :simple:installPlatformAndroid, :simple:transformDependenciesAndroid, :simple:resolveDependenciesAndroid, :simple:prepareAndroidRelease")
        result.assertStdoutContains("task :simple:prepareAndroidRelease -> :simple:installBuildToolsAndroid, installCmdlineTools, installPlatformTools, :simple:installPlatformAndroid, :simple:resolveDependenciesAndroid")
        result.assertStdoutContains("task :simple:runAndroidRelease -> :simple:installSystemImageAndroid, installEmulator, :simple:buildAndroidRelease")

        // transform dependencies
        // main
        result.assertStdoutContains("task :simple:transformDependenciesAndroid -> :simple:resolveDependenciesAndroid")
        // test
        result.assertStdoutContains("task :simple:transformDependenciesAndroidTest -> :simple:resolveDependenciesAndroidTest")
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

    /**
     * Regression test for KTC-5751.
     *
     * The runtime classpath of this project contains several pairs of artifacts that share the exact same file
     * name in different directories (e.g. `lifecycle-runtime-android-2.11.0.aar` published under both
     * `androidx.lifecycle` and `org.jetbrains.androidx.lifecycle`).
     *
     * AGP names its Java resource merger inputs after the Gradle component identifier of each runtime classpath
     *  entry and persists those names in the incremental merger state. Before the fix, the entries were passed to Gradle as
     * coordinate-less file dependencies, their identifiers only carried the file name, so these pairs become
     * indistinguishable, and the second (incremental) build fails with "Unknown file: META-INF/...".
     */
    @Test
    fun `incremental build succeeds when dependency artifacts share file names`() = runSlowTest {
        val taskName = ":duplicate-dependency-file-names:buildAndroidDebug"
        val result = runCli(
            projectDir = testProject("android/duplicate-dependency-file-names"),
            "task", taskName,
            configureAndroidHome = true,
        )

        // The runtime dependencies must reach Gradle with an identity that distinguishes same-named artifacts,
        // which their file name alone does not.
        val generatedSettings = result.getTaskOutputPath(taskName) / "gradle-project" / "settings.gradle.kts"
        val generatedSettingsText = generatedSettings.readText()
        val ids = Regex(""""id":"([^"]*)"""").findAll(generatedSettingsText).map { it.groupValues[1] }.toList()
        assertTrue(ids.isNotEmpty(), "No runtime dependency identities found in $generatedSettings")
        assertEquals(
            ids.size,
            ids.distinct().size,
            "Runtime dependencies were passed to Gradle with colliding identities: " +
                    ids.groupBy { it }.filterValues { it.size > 1 }.keys,
        )
        // Sanity check that this project does exercise the same-file-name case in the first place: the androidx and
        // the org.jetbrains.androidx flavours of this artifact are both on the runtime classpath.
        val sameNamedArtifact = "lifecycle-runtime-android-2.11.0.aar"
        assertEquals(
            2,
            ids.count { it.endsWith("/$sameNamedArtifact") },
            "Expected two distinct artifacts named $sameNamedArtifact among $ids",
        )

        val sourceFile = result.projectDir / "src" / "com" / "jetbrains" / "sample" / "app" / "MainActivity.kt"
        sourceFile.writeText(sourceFile.readText().replace("Hello", "Hello again"))

        // The incremental Gradle build must not fail on the colliding Java resource merger inputs.
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
