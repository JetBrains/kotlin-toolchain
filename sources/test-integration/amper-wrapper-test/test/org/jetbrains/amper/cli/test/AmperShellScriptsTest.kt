/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.events.sink.NoopEventSink
import org.jetbrains.amper.frontend.schema.JvmDistribution
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jdk.provisioning.JdkProvisioningCriteria
import org.jetbrains.amper.jdk.provisioning.orThrow
import org.jetbrains.amper.stdlib.hashing.sha256
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.test.AmperCliWithWrapperTestBase
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.JavaHomeMode
import org.jetbrains.amper.test.LocalAmperPublication
import org.jetbrains.amper.test.TempDirExtension
import org.jetbrains.amper.wrapper.AmperWrapperData
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import kotlin.io.path.PathWalkOption
import kotlin.io.path.copyToRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Tag("slow")
@Tag("cli-test-group-infra")
class AmperShellScriptsTest : AmperCliWithWrapperTestBase() {
    @RegisterExtension
    private val tempDirExtension = TempDirExtension()
    private val tempDir: Path
        get() = tempDirExtension.path

    private val cliScript: Path
        get() = tempDir.resolve(scriptNameForCurrentOs)

    private val shellScriptExampleProject = Dirs.amperTestProjectsRoot / "shell-scripts"

    @BeforeEach
    fun prepareScript() {
        LocalAmperPublication.setupWrappersIn(tempDir)
    }

    /**
     * It's expected on the start that wrappers and cli dist are published to maven local
     */
    @Test
    fun `shell script does not download or extract on subsequent run`() = runBlocking {
        val templatePath = shellScriptExampleProject
        assertTrue { templatePath.isDirectory() }

        templatePath.copyToRecursively(tempDir, followLinks = false, overwrite = false)

        val bootstrapCacheDir = tempDir.resolve("boot strap")

        val result1 = runAmper(
            workingDir = tempDir,
            args = listOf("run"),
            bootstrapCacheDir = bootstrapCacheDir,
            // We want to test the proper download of the JRE to the bootstrap dir, so we have to unset this
            amperJavaHomeMode = JavaHomeMode.ForceUnset,
        )
        assertTrue("Process output must contain 'Hello for Shell Scripts Test' the first time. Output:\n${result1.stdout}") {
            result1.stdout.contains("Hello for Shell Scripts Test")
        }
        assertTrue("Process output must contain welcome banner the first time. Output:\n${result1.stdout}") {
            result1.stdout.contains("Welcome")
        }
        assertTrue("Process output must contain the distribution download. Output:\n${result1.stdout}") {
            result1.stdout.contains("Downloading Kotlin Toolchain distribution")
        }
        assertTrue("Process output must contain the JRE download for the CLI. Output:\n${result1.stdout}") {
            result1.stdout.contains("Downloading Kotlin CLI runtime")
        }

        val result2 = runAmper(
            workingDir = tempDir,
            args = listOf("run"),
            bootstrapCacheDir = bootstrapCacheDir,
            // We want to test the check of JRE presence in the bootstrap dir, so we have to unset this
            amperJavaHomeMode = JavaHomeMode.ForceUnset,
        )
        assertTrue("Process output must contain 'Hello for Shell Scripts Test' the second time. Output:\n${result2.stdout}") {
            result2.stdout.contains("Hello for Shell Scripts Test")
        }
        val nDownloadingLines2 = result2.stdout.lines().count { it.startsWith("Downloading ") }
        assertEquals(
            0,
            nDownloadingLines2,
            "Process output must not have 'Downloading ' lines the second time, got $nDownloadingLines2. Output:\n${result2.stdout}"
        )

        assertEquals(
            expected = requestedFiles,
            actual = listOf(LocalAmperPublication.distTgz),
            message = "The Kotlin wrapper script run should request the Kotlin Toolchain distribution (and only this)",
        )
    }

    /**
     * It's expected on the start that wrappers and cli dist are published to maven local
     */
    @Test
    fun `shell script does not show the welcome banner if disabled`() = runBlocking {
        val templatePath = shellScriptExampleProject
        assertTrue { templatePath.isDirectory() }

        templatePath.copyToRecursively(tempDir, followLinks = false, overwrite = false)

        val bootstrapCacheDir = tempDir.resolve("boot strap")

        val result1 = runAmper(
            workingDir = tempDir,
            args = listOf("run"),
            bootstrapCacheDir = bootstrapCacheDir,
            // We want to test the proper download of the JRE to the bootstrap dir, so we have to unset this
            amperJavaHomeMode = JavaHomeMode.ForceUnset,
            configureEnvironment = { put("KOTLIN_CLI_NO_WELCOME_BANNER", "1") },
        )
        assertFalse("Process output must NOT contain welcome banner even the first time when disabled. Output:\n${result1.stdout}") {
            result1.stdout.contains("Welcome")
        }
    }

    /**
     * It's expected on the start that wrappers and cli dist are published to maven local
     */
    @Test
    fun `first runs should be able to work concurrently`() = runBlocking {
        val templatePath = shellScriptExampleProject
        assertTrue { templatePath.isDirectory() }
        templatePath.copyToRecursively(tempDir, followLinks = false, overwrite = false)

        val bootstrapCacheDir = tempDir.resolve("boot strap")

        withContext(Dispatchers.IO) {
            repeat(15) {
                launch {
                    runAmper(
                        workingDir = tempDir,
                        args = listOf("--version"),
                        bootstrapCacheDir = bootstrapCacheDir,
                        // We want to test concurrent downloads of the JRE to the bootstrap dir, so we have to unset this
                        amperJavaHomeMode = JavaHomeMode.ForceUnset,
                    )
                }
            }
        }
    }

    @Test
    fun `custom bootstrap cache`() = runBlocking {
        val templatePath = shellScriptExampleProject
        assertTrue { templatePath.isDirectory() }

        templatePath.copyToRecursively(tempDir, followLinks = false, overwrite = false)

        val bootstrapCacheDir = tempDir.resolve("my bootstrap cache")
        assertTrue("Bootstrap cache dir should start empty") {
            bootstrapCacheDir.notExists() || bootstrapCacheDir.listDirectoryEntries().isEmpty()
        }

        val result = runAmper(
            workingDir = tempDir,
            args = listOf("--version"),
            bootstrapCacheDir = bootstrapCacheDir,
            // We want to test the proper download of the JRE to the bootstrap dir, so we have to unset this
            amperJavaHomeMode = JavaHomeMode.ForceUnset,
        )
        val nDownloadingLines = result.stdout.lines().count { it.startsWith("Downloading ") }
        assertEquals(
            2,
            nDownloadingLines,
            "Process output must have 'Downloading ' line twice, got $nDownloadingLines. Output:\n${result.stdout}"
        )
        assertTrue("Bootstrap cache dir should now exist") {
            bootstrapCacheDir.exists()
        }
        assertTrue("Bootstrap cache dir should now have the CLI distribution, but got:\n" +
                bootstrapCacheDir.listDirectoryEntries().joinToString("\n")) {
            bootstrapCacheDir.listDirectoryEntries("kotlin-cli-*").isNotEmpty()
        }
        assertTrue("Bootstrap cache dir should now have the JRE, but got:\n" +
                bootstrapCacheDir.listDirectoryEntries().joinToString("\n")) {
            bootstrapCacheDir.listDirectoryEntries("zulu*").isNotEmpty()
        }
    }

    @Test
    fun `custom java home`() = runBlocking {
        val jdkHome = provisionZulu25()

        val expectedAmperVersion = AmperWrapperData.parseFromProjectRoot(tempDir)?.version
            ?: error("Test setup is broken, no wrapper file found in the temp directory")

        val result = runAmper(
            workingDir = tempDir,
            args = listOf("--version"),
            amperJavaHomeMode = JavaHomeMode.Custom(jreHomePath = jdkHome),
            bootstrapCacheDir = tempDir.resolve("boot strap"),
        )
        val expectedVersionString = Regex(
            Regex.escape("Kotlin Toolchain version $expectedAmperVersion ") + """\([A-Fa-f0-9]+, \d{4}-\d{2}-\d{2}\)"""
        )

        assertTrue("Process output must contain '${expectedVersionString.pattern}'. Output:\n${result.stdout}") {
            result.stdout.lines().any { expectedVersionString.matches(it) }
        }

        val nDownloadingLines = result.stdout.lines().count { it.startsWith("Downloading ") }
        assertEquals(
            1,
            nDownloadingLines,
            "Process output must have 'Downloading ' line only once (for Kotlin CLI itself), got $nDownloadingLines. Output:\n${result.stdout}"
        )

        // TODO Somehow assert that exactly this JRE is used by amper bootstrap
    }

    private suspend fun provisionZulu25(): Path {
        val fakeUserCacheRoot = AmperUserCacheRoot(Dirs.userCacheRoot)
        val incrementalCache = IncrementalCache(stateRoot = tempDir, codeVersion = "1")
        val jdkResult = context(NoopEventSink) {
            JdkProvider(fakeUserCacheRoot, incrementalCache = incrementalCache).provisionJdk(
                JdkProvisioningCriteria(
                    majorVersion = 25,
                    distributions = listOf(JvmDistribution.AzulZulu),
                )
            )
        }
        return jdkResult.orThrow().homeDir
    }

    @Test
    fun `fails on wrong amper distribution checksum`() = runBlocking {
        val data = AmperWrapperData.parseFromProjectRoot(tempDir)
            ?: error("Test setup is broken, no wrapper file found in the temp directory")
        cliScript.writeText(
            cliScript.readText()
                .replace(data.sha256, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        )
        val bootstrapCacheDir = tempDir.resolve("boot strap")
        assertTrue("Bootstrap cache dir should start empty") {
            bootstrapCacheDir.notExists() || bootstrapCacheDir.listDirectoryEntries().isEmpty()
        }
        val result = runAmper(
            workingDir = tempDir,
            args = listOf("--version"),
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            bootstrapCacheDir = bootstrapCacheDir,
            amperJavaHomeMode = JavaHomeMode.ForceUnset,
        )
        val expectedErrorRegex =
            Regex("""ERROR: Checksum mismatch for .+ \(downloaded from .+\): expected checksum aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa but got \w+""")
        assertTrue("Process stderr must match '$expectedErrorRegex'. Actual stderr:\n${result.stderr}") {
            // cmd.exe breaks lines unpredictably when calling powershell (it depends on its own buffer)
            result.stderr.replace("\r", "").replace("\n", "").matches(expectedErrorRegex)
        }
    }

    @Test
    fun `fails on wrong jre distribution checksum`() = runBlocking {
        val bootstrapCacheDir = tempDir.resolve("boot strap")

        // Step 1: Run normally to download and extract the Amper distribution (with a custom JAVA_HOME to skip JRE download)
        val jdkHome = provisionZulu25()
        runAmper(
            workingDir = tempDir,
            args = listOf("--version"),
            bootstrapCacheDir = bootstrapCacheDir,
            amperJavaHomeMode = JavaHomeMode.Custom(jreHomePath = jdkHome),
        )

        // Step 2: Find the launcher script inside the extracted distribution and corrupt jre_sha256
        val amperDistDirs = bootstrapCacheDir.listDirectoryEntries("kotlin-cli-*")
        assertEquals(1, amperDistDirs.size, "Expected exactly one kotlin-cli distribution dir in $bootstrapCacheDir")
        val launcherScriptName = "launcher.sh"
        val launcherScript = amperDistDirs.single() / "bin" / launcherScriptName
        assertTrue(launcherScript.exists(), "Launcher script not found at $launcherScript")

        val checksumRegex = Regex("\\b(jre_sha256=)[0-9a-fA-F]+")
        val originalContent = launcherScript.readText()
        val corruptedContent = originalContent
            .replace(checksumRegex, "\$1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertNotEquals(originalContent, corruptedContent, "jre_sha256 pattern was not found in the launcher script")
        launcherScript.writeText(corruptedContent)
        launcherScript.toFile().setExecutable(true)

        // Step 3: Remove any already-provisioned JRE directories and their .flag files so the launcher re-downloads
        bootstrapCacheDir.listDirectoryEntries("zulu*").forEach { it.toFile().deleteRecursively() }

        // Step 4: Run again without KOTLIN_CLI_JAVA_HOME, expecting a checksum failure from the launcher
        val result = runAmper(
            workingDir = tempDir,
            args = listOf("--version"),
            expectedExitCode = 1,
            assertEmptyStdErr = false,
            bootstrapCacheDir = bootstrapCacheDir,
            amperJavaHomeMode = JavaHomeMode.ForceUnset,
        )
        val expectedErrorRegex =
            Regex("""ERROR: Checksum mismatch for .+ \(downloaded from .+\): expected checksum aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa but got \w+""")
        assertTrue("Process stderr must match '$expectedErrorRegex'. Actual stderr:\n${result.stderr}") {
            // cmd.exe breaks lines unpredictably when calling powershell (it depends on its own buffer)
            result.stderr.replace("\r", "").replace("\n", "").matches(expectedErrorRegex)
        }
    }

    @Test
    fun `the provisioned JRE doesn't contain compromised CA certs`() = runBlocking {
        val bootstrapCacheDir = tempDir.resolve("boot strap")

        runAmper(workingDir = tempDir, args = ["--version"], bootstrapCacheDir = bootstrapCacheDir)

        val jreExtractDir = bootstrapCacheDir.listDirectoryEntries("zulu*").singleOrNull()
        assertNotNull(jreExtractDir, "a JRE should be provisioned")

        val caCertsStore = jreExtractDir.findRealJreHome().resolve("lib/security/cacerts")

        @Suppress("BlockingMethodInNonBlockingContext") // we are literally in runBlocking, we're fine.
        val keyStore = KeyStore.getInstance(caCertsStore.toFile(), "changeit".toCharArray())
        val compromisedCerts = keyStore.findCertsWithFingerprints([
            "86:A1:EC:BA:08:9C:4A:8D:3B:BE:27:34:C6:12:BA:34:1D:81:3E:04:3C:F9:E8:A8:62:CD:5C:57:A3:6B:BE:6B",
            "D7:A7:A0:FB:5D:7E:27:31:D7:71:E9:48:4E:BC:DE:F7:1D:5F:0C:3E:0A:29:48:78:2B:C8:3E:E0:EA:69:9E:F4",
        ])
        assertTrue(
            compromisedCerts.isEmpty(),
            "The provisioned JRE should not contain compromised certificates, got:\n" +
                    compromisedCerts.joinToString("\n") {
                        "- Issuer: ${it.issuerX500Principal}\n  Subject: ${it.subjectX500Principal}\n  Fingerprint: ${it.fingerprint}"
                    }
        )
    }

    private fun Path.findRealJreHome(): Path {
        val javaBinaryName = if (OsFamily.current.isWindows) "java.exe" else "java"
        return walk(PathWalkOption.BREADTH_FIRST, PathWalkOption.INCLUDE_DIRECTORIES)
            .firstOrNull { it.resolve("bin/$javaBinaryName").exists() }
            ?: error("JRE home not found")
    }

    private fun KeyStore.findCertsWithFingerprints(fingerprints: Set<String>): List<X509Certificate> = aliases()
        .asSequence()
        .flatMap { alias ->
            getAllReachableCertificates(alias)
                .filterIsInstance<X509Certificate>()
                .filter { it.basicConstraints >= 0 && it.fingerprint in fingerprints }
        }
        .distinct()
        .toList()

    private fun KeyStore.getAllReachableCertificates(alias: String): List<Certificate> = buildList {
        getCertificate(alias)?.let { add(it) }
        getCertificateChain(alias)?.let { addAll(it) }
    }

    val fingerprintFormat = HexFormat {
        upperCase = true
        bytes {
            byteSeparator = ":"
        }
    }

    private val X509Certificate.fingerprint: String
        get() = encoded.sha256().toHexString(fingerprintFormat)
}
