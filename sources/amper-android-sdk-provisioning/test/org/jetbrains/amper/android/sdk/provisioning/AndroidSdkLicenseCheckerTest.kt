/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import com.android.repository.api.License
import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSdkLicenseCheckerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `package manifests are found without descending into packages`() {
        val sdkRoot = tempDir / "sdk"
        val firstManifest = (sdkRoot / "build-tools" / "36.0.0" / "package.xml").createFile()
        val secondManifest = (sdkRoot / "system-images" / "android-36" / "google_apis" / "x86_64" / "package.xml")
            .createFile()

        // An SDK package is rooted at its package.xml, so this nested manifest is not a separate package.
        (firstManifest.parent / "nested" / "package.xml").createFile()
        (sdkRoot / "platform-tools" / "not-a-package.xml").createFile()

        assertEquals(
            setOf(firstManifest, secondManifest),
            findAndroidSdkPackageManifests(sdkRoot),
        )
    }

    @Test
    fun `no package manifests are found in an empty SDK`() {
        val sdkRoot = (tempDir / "empty-sdk").also { it.createDirectories() }

        assertEquals(emptySet(), findAndroidSdkPackageManifests(sdkRoot))
    }

    @Test
    fun `unaccepted SDK licenses include the packages that require them`() = runBlocking {
        val sdkRoot = (tempDir / "sdk").also { it.createDirectories() }
        writeManifest(sdkRoot, "platforms/android-36", licenseId = "license-z")
        writeManifest(sdkRoot, "build-tools/36.0.0", licenseId = "license-a")
        writeManifest(sdkRoot, "cmdline-tools/latest", licenseId = "license-a")
        val checker = AndroidSdkLicenseChecker(sdkRoot, createIncrementalCache()) { manifest ->
            manifest.readTestPackageLicense(sdkRoot)
        }

        assertEquals(
            AndroidSdkLicenseCheckResult.Unaccepted(
                packagesByLicenseId = mapOf(
                    "license-a" to listOf("build-tools;36.0.0", "cmdline-tools;latest"),
                    "license-z" to listOf("platforms;android-36"),
                ),
            ),
            checker.check(),
        )
    }

    @Test
    fun `unchanged SDK reuses cached unaccepted licenses without parsing manifests`() = runBlocking {
        val sdkRoot = (tempDir / "sdk").also { it.createDirectories() }
        val stateRoot = tempDir / "incremental-state"
        writeManifest(sdkRoot, "platforms/android-36", licenseId = "license-z")
        writeManifest(sdkRoot, "build-tools/36.0.0", licenseId = "license-a")
        writeManifest(sdkRoot, "cmdline-tools/22.0", licenseId = "license-a")
        var parserInvocations = 0
        fun checker() = AndroidSdkLicenseChecker(
            androidSdkPath = sdkRoot,
            incrementalCache = createIncrementalCache(stateRoot),
        ) { manifest ->
            parserInvocations++
            manifest.readTestPackageLicense(sdkRoot)
        }

        assertEquals(listOf("license-a", "license-z"), checker().findUnacceptedLicenseIds())
        assertEquals(3, parserInvocations)

        // Use a new cache instance, as a new CLI invocation would.
        assertEquals(listOf("license-a", "license-z"), checker().findUnacceptedLicenseIds())
        assertEquals(3, parserInvocations, "manifests must not be parsed on a cache hit")
    }

    @Test
    fun `creating changing and removing a license file invalidates the cached result`() = runBlocking {
        val sdkRoot = (tempDir / "sdk").also { it.createDirectories() }
        val manifest = writeManifest(sdkRoot, "platforms/android-36", licenseId = "license-a")
        var parserInvocations = 0
        val checker = AndroidSdkLicenseChecker(sdkRoot, createIncrementalCache()) { path ->
            parserInvocations++
            path.readTestPackageLicense(sdkRoot)
        }

        assertEquals(listOf("license-a"), checker.findUnacceptedLicenseIds())
        assertEquals(1, parserInvocations)

        val acceptedLicense = manifest.readTestLicense()
        val licenseFile = sdkRoot / "licenses" / acceptedLicense.id
        licenseFile.createParentDirectories()
        licenseFile.writeText(acceptedLicense.licenseHash)
        assertEquals(emptyList(), checker.findUnacceptedLicenseIds())
        assertEquals(2, parserInvocations)

        licenseFile.writeText("not accepted")
        assertEquals(listOf("license-a"), checker.findUnacceptedLicenseIds())
        assertEquals(3, parserInvocations)

        licenseFile.writeText(acceptedLicense.licenseHash)
        assertEquals(emptyList(), checker.findUnacceptedLicenseIds())
        assertEquals(4, parserInvocations)

        licenseFile.deleteExisting()
        assertEquals(listOf("license-a"), checker.findUnacceptedLicenseIds())
        assertEquals(5, parserInvocations)
    }

    @Test
    fun `adding editing replacing and deleting package manifests invalidates the cached result`() = runBlocking {
        val sdkRoot = (tempDir / "sdk").also { it.createDirectories() }
        val firstManifest = writeManifest(sdkRoot, "platforms/android-36", licenseId = "license-a")
        var parserInvocations = 0
        val checker = AndroidSdkLicenseChecker(sdkRoot, createIncrementalCache()) { path ->
            parserInvocations++
            path.readTestPackageLicense(sdkRoot)
        }

        assertEquals(listOf("license-a"), checker.findUnacceptedLicenseIds())
        assertEquals(1, parserInvocations)

        val secondManifest = writeManifest(sdkRoot, "build-tools/36.0.0", licenseId = "license-b")
        assertEquals(listOf("license-a", "license-b"), checker.findUnacceptedLicenseIds())
        assertEquals(3, parserInvocations)

        secondManifest.writeText("license-c-with-a-longer-id|edited terms")
        assertEquals(listOf("license-a", "license-c-with-a-longer-id"), checker.findUnacceptedLicenseIds())
        assertEquals(5, parserInvocations)

        val replacementManifest = (tempDir / "replacement.xml").apply {
            writeText("license-d-with-an-even-longer-id|replacement terms")
        }
        replacementManifest.moveTo(secondManifest, overwrite = true)
        assertEquals(listOf("license-a", "license-d-with-an-even-longer-id"), checker.findUnacceptedLicenseIds())
        assertEquals(7, parserInvocations)

        firstManifest.deleteExisting()
        assertEquals(listOf("license-d-with-an-even-longer-id"), checker.findUnacceptedLicenseIds())
        assertEquals(8, parserInvocations)
    }

    @Test
    fun `checker instances for the same SDK share one cache entry`() = runBlocking {
        val sdkRoot = (tempDir / "sdk").also { it.createDirectories() }
        writeManifest(sdkRoot, "platforms/android-36", licenseId = "license-a")
        val incrementalCache = createIncrementalCache()
        var firstParserInvocations = 0
        var secondParserInvocations = 0
        val firstChecker = AndroidSdkLicenseChecker(sdkRoot, incrementalCache) { path ->
            firstParserInvocations++
            path.readTestPackageLicense(sdkRoot)
        }
        val secondChecker = AndroidSdkLicenseChecker(sdkRoot, incrementalCache) { path ->
            secondParserInvocations++
            path.readTestPackageLicense(sdkRoot)
        }

        assertEquals(listOf("license-a"), firstChecker.findUnacceptedLicenseIds())
        assertEquals(listOf("license-a"), secondChecker.findUnacceptedLicenseIds())
        assertEquals(1, firstParserInvocations)
        assertEquals(0, secondParserInvocations)
    }

    @Test
    fun `cache entries are namespaced by normalized SDK path`() = runBlocking {
        val firstSdkRoot = (tempDir / "first-sdk").also { it.createDirectories() }
        val secondSdkRoot = (tempDir / "second-sdk").also { it.createDirectories() }
        writeManifest(firstSdkRoot, "platforms/android-36", licenseId = "license-a")
        writeManifest(secondSdkRoot, "platforms/android-36", licenseId = "license-b")
        val stateRoot = tempDir / "incremental-state"
        var parserInvocations = 0

        fun checker(sdkRoot: Path) = AndroidSdkLicenseChecker(
            androidSdkPath = sdkRoot,
            incrementalCache = createIncrementalCache(stateRoot),
        ) { path ->
            parserInvocations++
            path.readTestPackageLicense(sdkRoot)
        }

        assertEquals(listOf("license-a"), checker(firstSdkRoot).findUnacceptedLicenseIds())
        assertEquals(listOf("license-b"), checker(secondSdkRoot).findUnacceptedLicenseIds())
        assertEquals(listOf("license-a"), checker(firstSdkRoot).findUnacceptedLicenseIds())
        assertEquals(2, parserInvocations)
    }

    @Test
    fun `corrupt incremental state causes license validation to be recalculated`() = runBlocking {
        val sdkRoot = (tempDir / "sdk").also { it.createDirectories() }
        writeManifest(sdkRoot, "platforms/android-36", licenseId = "license-a")
        val stateRoot = tempDir / "incremental-state"
        var parserInvocations = 0
        val checker = AndroidSdkLicenseChecker(
            androidSdkPath = sdkRoot,
            incrementalCache = IncrementalCache(stateRoot, codeVersion = "test"),
        ) { path ->
            parserInvocations++
            path.readTestPackageLicense(sdkRoot)
        }

        assertEquals(listOf("license-a"), checker.findUnacceptedLicenseIds())
        assertEquals(1, parserInvocations)

        stateRoot.listDirectoryEntries().single().writeText("corrupt state")
        assertEquals(listOf("license-a"), checker.findUnacceptedLicenseIds())
        assertEquals(2, parserInvocations)
    }

    private fun createIncrementalCache(
        stateRoot: Path = tempDir / "incremental-state",
    ) = IncrementalCache(
        stateRoot = stateRoot,
        codeVersion = "test",
    )

    private fun writeManifest(sdkRoot: Path, packagePath: String, licenseId: String): Path =
        (sdkRoot / packagePath / "package.xml").apply {
            createParentDirectories()
            writeText("$licenseId|terms for $licenseId")
        }

    private fun Path.readTestLicense(): License {
        val parts = readText().split('|', limit = 2)
        return TestLicense(parts[0], parts[1])
    }

    private fun Path.readTestPackageLicense(sdkRoot: Path): AndroidSdkPackageLicense = AndroidSdkPackageLicense(
        packagePath = sdkRoot.relativize(parent).toString().replace('/', ';'),
        license = readTestLicense(),
    )

    private suspend fun AndroidSdkLicenseChecker.findUnacceptedLicenseIds(): List<String> =
        when (val result = check()) {
            AndroidSdkLicenseCheckResult.Accepted -> emptyList()
            is AndroidSdkLicenseCheckResult.Unaccepted -> result.packagesByLicenseId.keys.toList()
        }

    private fun Path.createFile(): Path = apply {
        createParentDirectories()
        writeText("")
    }

    private class TestLicense(
        private var licenseId: String,
        private var licenseText: String,
    ) : License() {
        override fun getId(): String = licenseId

        override fun setId(id: String) {
            licenseId = id
        }

        override fun getValue(): String = licenseText

        override fun setValue(value: String) {
            licenseText = value
        }
    }
}
