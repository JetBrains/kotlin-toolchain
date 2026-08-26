/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.NoopProblemReporter
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.TempDirExtension
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

class AndroidSdkProviderTest {

    @RegisterExtension
    private val tempDirExtension = TempDirExtension()

    private val systemImageAbi = when (Arch.current) {
        Arch.X64 -> AndroidSdkPackageRequest.SystemImage.ImageAbi.X86_64
        Arch.Arm64 -> AndroidSdkPackageRequest.SystemImage.ImageAbi.Arm64V8A
    }

    private val sdkRoot get() = tempDirExtension.path / "sdk"

    @DisplayName("Valid request for each kind of package")
    @Nested
    inner class EachRequestKindCanProvisionThePackage {
        @Test
        fun `platform tools`() = runTestWithMdc(timeout = 10.minutes) {
            createTestProvider().assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools)
        }

        @Test
        fun emulator() = runTestWithMdc(timeout = 10.minutes) {
            createTestProvider().assertSuccessProvisioning(AndroidSdkPackageRequest.Emulator)
        }

        @Test
        fun `command-line tools`() = runTestWithMdc(timeout = 10.minutes) {
            createTestProvider().assertSuccessProvisioning(AndroidSdkPackageRequest.CommandLineTools(version = "latest"))
        }

        @Test
        fun `build tools`() = runTestWithMdc(timeout = 10.minutes) {
            createTestProvider().assertSuccessProvisioning(AndroidSdkPackageRequest.BuildTools(version = "37.0.0"))
        }

        @Test
        fun platform() = runTestWithMdc(timeout = 10.minutes) {
            createTestProvider().assertSuccessProvisioning(AndroidSdkPackageRequest.Platform(apiLevel = 37, minorApiLevel = 0))
        }

        @Test
        fun `platform sources`() = runTestWithMdc(timeout = 10.minutes) {
            createTestProvider().assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformSources(apiLevel = 37, minorApiLevel = 0))
        }

        @Test
        fun `system image`() = runTestWithMdc(timeout = 10.minutes) {
            val request = AndroidSdkPackageRequest.SystemImage(
                minimalAcceptableApiLevel = 37,
                tag = AndroidSdkPackageRequest.SystemImage.ServicesTag.GoogleApis,
                abi = systemImageAbi,
            )
            createTestProvider().assertSuccessProvisioning(request)
        }
    }

    @Test
    fun `installs a fresh package from the remote repository`() = runTestWithMdc(timeout = 10.minutes) {
        val setup = createTestProvider()
        val androidPackage = setup.assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools)
        assertEquals(PackagePath("platform-tools"), androidPackage.packagePath)

        assertEquals(1, setup.spans(downloadSpanName("platform-tools")).size, "the package should be downloaded once")
        val provisionSpan = setup.spans(provisionSpanName(AndroidSdkPackageRequest.PlatformTools)).single()
        assertEquals(false, provisionSpan.attributes.get(fromMemoryCacheKey))
    }

    @Test
    fun `serves repeated requests for the same package from the in-memory cache`() = runTestWithMdc(timeout = 10.minutes) {
        val setup = createTestProvider()

        setup.assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools)
        setup.assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools)

        assertEquals(
            1, setup.spans(downloadSpanName("platform-tools")).size,
            "the package should only be downloaded once, the second request should be served from the in-memory cache",
        )

        val provisionSpans = setup.spans(provisionSpanName(AndroidSdkPackageRequest.PlatformTools))
        assertEquals(2, provisionSpans.size)
        assertEquals(false, provisionSpans[0].attributes.get(fromMemoryCacheKey))
        assertEquals(true, provisionSpans[1].attributes.get(fromMemoryCacheKey))
    }

    @Test
    fun `reads an already-installed local package without contacting the network`() = runTestWithMdc(timeout = 10.minutes) {
        val setupA = createTestProvider(incrementalCacheStateRoot = tempDirExtension.path / "inc-a")
        setupA.assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools)

        // A brand-new provider instance simulates a separate CLI invocation reusing a previously provisioned SDK root.
        val setupB = createTestProvider(incrementalCacheStateRoot = tempDirExtension.path / "inc-b")
        setupB.assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools)

        assertEquals(1, setupB.spans(readLocalPackageSpanName).size)
        assertTrue(
            setupB.spans(downloadSpanName("platform-tools")).isEmpty(),
            "a locally installed package with a valid manifest should not be re-downloaded",
        )
        assertTrue(
            setupB.spans(readRepositorySpanName("main")).isEmpty(),
            "a locally installed package with a valid manifest should not require fetching the repository listing at all",
        )
    }

    @Test
    fun `redownloads and reports a problem when the local package manifest is missing`() = runTestWithMdc(timeout = 10.minutes) {
        val setupA = createTestProvider(incrementalCacheStateRoot = tempDirExtension.path / "inc-a")
        setupA.assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools)

        // Simulate a corrupted/partial local installation: the package directory is there, but its manifest isn't.
        (sdkRoot / "platform-tools" / "package.xml").deleteExisting()

        val setupB = createTestProvider(incrementalCacheStateRoot = tempDirExtension.path / "inc-b")
        val reporter = CollectingProblemReporter()
        setupB.assertSuccessProvisioning(AndroidSdkPackageRequest.PlatformTools, reporter)

        val problem = reporter.problems.single()
        assertTrue(problem is LocalPackageWithoutManifest, "expected a LocalPackageWithoutManifest problem, got: $problem")
        assertEquals(PackagePath("platform-tools"), problem.packageName)
        assertEquals(sdkRoot / "platform-tools", problem.packagePath)

        assertEquals(
            1, setupB.spans(downloadSpanName("platform-tools")).size,
            "the package should be re-downloaded once the local manifest is found to be missing",
        )
    }

    @Test
    fun `returns an error when the requested package version does not exist`() = runTestWithMdc(timeout = 3.minutes) {
        val setup = createTestProvider()
        val request = AndroidSdkPackageRequest.BuildTools("0.0.0-does-not-exist")

        val error = setup.assertErrorProvisioning(request)
        assertEquals("Failed to provision Android SDK Build-Tools `0.0.0-does-not-exist`", error)

        assertTrue(setup.spans(downloadSpanName("build-tools;0.0.0-does-not-exist")).isEmpty())
        assertTrue(setup.spans(readRepositorySpanName("main")).isNotEmpty())
    }

    @Test
    fun `reuses the fetched repository listing across different package requests`() = runTestWithMdc(timeout = 3.minutes) {
        val setup = createTestProvider()
        setup.assertErrorProvisioning(AndroidSdkPackageRequest.BuildTools("0.0.0-does-not-exist"))
        setup.assertErrorProvisioning(AndroidSdkPackageRequest.CommandLineTools("0.0-does-not-exist"))

        assertEquals(
            1, setup.spans(fetchRepositoryXmlSpanName("main")).size,
            "the repository XML list should only be fetched once for both requests",
        )
        val repositorySpans = setup.spans(readRepositorySpanName("main"))
        assertEquals(2, repositorySpans.size)
        assertEquals(false, repositorySpans[0].attributes.get(fromMemoryCacheKey))
        assertEquals(true, repositorySpans[1].attributes.get(fromMemoryCacheKey))
    }

    @Test
    fun `reuses the persisted repository listing across separate provider instances`() = runTestWithMdc(timeout = 3.minutes) {
        val sharedIncrementalCache = tempDirExtension.path / "inc-shared"

        val setupA = createTestProvider(tempDirExtension.path / "sdk-a", sharedIncrementalCache)
        setupA.assertErrorProvisioning(AndroidSdkPackageRequest.BuildTools("0.0.0-does-not-exist"))
        assertEquals(1, setupA.spans(fetchRepositoryXmlSpanName("main")).size)

        // A fresh provider instance with an empty in-memory cache, but pointed at the same on-disk incremental cache,
        // simulating a separate CLI invocation running shortly after the one above.
        val setupB = createTestProvider(tempDirExtension.path / "sdk-b", sharedIncrementalCache)
        setupB.assertErrorProvisioning(AndroidSdkPackageRequest.BuildTools("0.0.0-does-not-exist-b"))

        assertTrue(
            setupB.spans(fetchRepositoryXmlSpanName("main")).isEmpty(),
            "the repository XML list should be served from the incremental cache, not re-fetched from the network",
        )
        val incrementalCacheSpan = setupB.spans(incrementalCacheRunSpanName("main")).single()
        assertEquals("up-to-date", incrementalCacheSpan.attributes.get(statusKey))
    }

    @Test
    fun `platform display name includes an explicit minor api level at api 37 and above`() = runTestWithMdc(timeout = 3.minutes) {
        val setup = createTestProvider()
        val error = setup.assertErrorProvisioning(AndroidSdkPackageRequest.Platform(apiLevel = 99, minorApiLevel = 0))
        assertEquals("Failed to provision Android Platform `99.0`", error)
    }

    @Test
    fun `platform display name includes a nonzero minor api level and sdk extension`() = runTestWithMdc(timeout = 3.minutes) {
        val setup = createTestProvider()
        val error = setup.assertErrorProvisioning(AndroidSdkPackageRequest.Platform(apiLevel = 99, minorApiLevel = 2, sdkExtension = 5))
        assertEquals("Failed to provision Android Platform `99.2-ext5`", error)
    }

    @Test
    fun `platform sources display name includes the minor api level`() = runTestWithMdc(timeout = 3.minutes) {
        val setup = createTestProvider()
        val error = setup.assertErrorProvisioning(AndroidSdkPackageRequest.PlatformSources(apiLevel = 99, minorApiLevel = 3))
        assertEquals("Failed to provision Android Platform `99.3` sources", error)
    }

    @Test
    fun `command-line tools display name includes the version`() = runTestWithMdc(timeout = 3.minutes) {
        val setup = createTestProvider()
        val error = setup.assertErrorProvisioning(AndroidSdkPackageRequest.CommandLineTools("0.0-does-not-exist"))
        assertEquals("Failed to provision Android SDK Command-Line Tools `0.0-does-not-exist`", error)
    }

    @Test
    fun `system image display name includes abi, api level, and services tag`() = runTestWithMdc(timeout = 3.minutes) {
        val setup = createTestProvider()
        val request = AndroidSdkPackageRequest.SystemImage(
            minimalAcceptableApiLevel = 9999,
            tag = AndroidSdkPackageRequest.SystemImage.ServicesTag.GoogleApis,
            abi = AndroidSdkPackageRequest.SystemImage.ImageAbi.Arm64V8A,
        )
        val error = setup.assertErrorProvisioning(request)
        assertEquals(
            "Failed to provision Android ARM64 system image API level `9999+` (Google APIs services)",
            error,
        )
    }

    @Test
    fun `system image is read from a local install without contacting the remote repository`() = runTestWithMdc(timeout = 10.minutes) {
        val request = AndroidSdkPackageRequest.SystemImage(
            minimalAcceptableApiLevel = 37,
            tag = AndroidSdkPackageRequest.SystemImage.ServicesTag.GoogleApis,
            abi = systemImageAbi,
        )
        val setupA = createTestProvider(incrementalCacheStateRoot =  tempDirExtension.path / "inc-a")
        setupA.assertSuccessProvisioning(request)

        val setupB = createTestProvider(incrementalCacheStateRoot = tempDirExtension.path / "inc-b")
        setupB.assertSuccessProvisioning(request)

        assertEquals(1, setupB.spans(readLocalPackageSpanName).size)
        assertTrue(
            setupB.spans(readRepositorySpanName("system-images")).isEmpty(),
            "an already-installed system image should be read locally without ever contacting the system images repository",
        )
    }

    @Test
    fun `system image with a lower minimum api level reuses a locally installed newer image`() = runTestWithMdc(timeout = 10.minutes) {
        val setupA = createTestProvider(incrementalCacheStateRoot = tempDirExtension.path / "inc-a")
        val installedPackage = setupA.assertSuccessProvisioning(
            AndroidSdkPackageRequest.SystemImage(
                minimalAcceptableApiLevel = 37,
                tag = AndroidSdkPackageRequest.SystemImage.ServicesTag.GoogleApis,
                abi = systemImageAbi,
            ),
        )

        val setupB = createTestProvider(incrementalCacheStateRoot = tempDirExtension.path / "inc-b")
        val reusedPackage = setupB.assertSuccessProvisioning(
            AndroidSdkPackageRequest.SystemImage(
                minimalAcceptableApiLevel = 35,
                tag = AndroidSdkPackageRequest.SystemImage.ServicesTag.GoogleApis,
                abi = systemImageAbi,
            ),
        )

        assertEquals(installedPackage.packagePath, reusedPackage.packagePath)
        assertEquals(1, setupB.spans(readLocalPackageSpanName).size)
        assertTrue(
            setupB.spans(readRepositorySpanName("system-images")).isEmpty(),
            "a locally installed newer system image should satisfy a lower minimum API level without contacting the repository",
        )
    }

    private class TestProviderSetup(val provider: AndroidSdkProvider, private val exporter: InMemorySpanExporter) {
        fun spans(name: String): List<SpanData> = exporter.finishedSpanItems.filter { it.name == name }
    }

    private fun createTestProvider(
        sdkRoot: Path = this.sdkRoot,
        incrementalCacheStateRoot: Path = tempDirExtension.path / "inc",
    ): TestProviderSetup {
        val exporter = InMemorySpanExporter.create()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
            .build()
        val provider = AndroidSdkProvider(
            userCacheRoot = AmperUserCacheRoot(Dirs.userCacheRoot),
            incrementalCache = IncrementalCache(
                stateRoot = incrementalCacheStateRoot,
                codeVersion = "1",
                openTelemetry = openTelemetry,
            ),
            openTelemetry = openTelemetry,
            sdkRoot = sdkRoot,
        )
        return TestProviderSetup(provider, exporter)
    }

    private suspend fun TestProviderSetup.assertSuccessProvisioning(
        request: AndroidSdkPackageRequest,
        reporter: ProblemReporter = NoopProblemReporter,
    ): AndroidSdkPackage {
        val result = context(reporter) { provider.provision(request) }
        return when (result) {
            is AndroidSdkResult.Success -> result.androidPackage
            is AndroidSdkResult.Error -> fail("expected a successful provisioning result but got an error: ${result.message}")
        }.also { androidPackage ->
            assertTrue(androidPackage.location.isDirectory())
            assertTrue(androidPackage.location.listDirectoryEntries().isNotEmpty())
            assertTrue(androidPackage.license.id.isNotBlank())
        }
    }

    private suspend fun TestProviderSetup.assertErrorProvisioning(
        request: AndroidSdkPackageRequest,
    ): String {
        val result = context(NoopProblemReporter) { provider.provision(request) }
        return when (result) {
            is AndroidSdkResult.Error -> result.message
            is AndroidSdkResult.Success -> fail(
                "expected a provisioning error but the package was provisioned successfully: ${result.androidPackage.packagePath}",
            )
        }
    }
}

private val fromMemoryCacheKey: AttributeKey<Boolean> = AttributeKey.booleanKey("from-memory-cache")
private val statusKey: AttributeKey<String> = AttributeKey.stringKey("status")

private const val readLocalPackageSpanName = "Read local Android SDK package"
private fun provisionSpanName(request: AndroidSdkPackageRequest) = "Provision Android SDK package $request"
private fun downloadSpanName(packagePath: String) = "Download Android SDK package $packagePath"
private fun readRepositorySpanName(repositoryName: String) = "Read Android repository $repositoryName"
private fun fetchRepositoryXmlSpanName(repositoryName: String) = "Fetch Android SDK $repositoryName repository XML list"
private fun incrementalCacheRunSpanName(repositoryName: String) = "inc: run: android-$repositoryName"
