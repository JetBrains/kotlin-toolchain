/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.publication.ios

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.MacOnly
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests of `kotlin publish testflight`.
 *
 * * App Store Connect is replaced by a [FakeAppStoreConnect] server, selected with `KOTLIN_TOOLCHAIN_ASC_API_URL`.
 * * `xcodebuild` is replaced by a shadow Xcode developer directory ([FakeXcode]), selected with `DEVELOPER_DIR`.
 *   It fakes `archive` and the upload and passes everything else to the real Xcode.
 *
 * The tests run with piped stdio, so the setup wizard is non-interactive and stops with a fallback message whenever
 * user input would be required.
 */
@Ignore // KTC-6034
@Tag("cli-test-group-ios")
@MacOnly
class IosTestflightNonInteractivePublishTest : CliTestBase() {

    private val key = AscTestKey()

    @Test
    fun `build works with the shadow Xcode`() = runSlowTest {
        val log = tempRoot / "xcodebuild.log"
        runCli(
            projectDir = testProject(TEST_PROJECT),
            "build", "-p", "iosSimulatorArm64",
            assertEmptyStdErr = false,
            configureEnvironment = { FakeXcode.configureEnvironment(this, ascUrl = "http://127.0.0.1:1", log = log) },
        )
        val invocations = FakeXcode.readInvocations(log)
        assertTrue(invocations.any { "-version" in it }, "xcodebuild -version should go through the fake: $invocations")
        assertTrue(invocations.any { "build" in it }, "xcodebuild build should go through the fake: $invocations")
    }

    @Test
    fun `happy path with existing bundle ID and app`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(asc)

            run.result.assertOutputContains("Setup is done")
            run.result.assertOutputContains("`$APP_NAME` has been uploaded!")
            run.result.assertOutputContains("`$APP_NAME` is ready!")
            asc.assertAllRequestsAuthorized()
            asc.assertNoUnexpectedRequests()
            assertFalse(asc.requests.any { it.method == "POST" }, "No resources should be created: ${asc.requests}")
            assertEquals(1, asc.uploads.size, "Exactly one upload is expected: ${asc.uploads}")
            assertTrue(asc.requests.any { it.path == "/v1/buildUploads/${asc.uploads.single().id}" })

            val archive = run.xcodebuildInvocations.single { "archive" in it }
            assertContains(archive, "-allowProvisioningUpdates")
            assertArgument(archive, "-configuration", "Release")
            assertArgument(archive, "-destination", "generic/platform=iOS")
            val upload = run.xcodebuildInvocations.single { "-exportArchive" in it }
            assertContains(upload, "-allowProvisioningUpdates")
            assertArgument(upload, "-authenticationKeyID", key.keyId)
            assertArgument(upload, "-authenticationKeyIssuerID", key.issuerId)
            assertArgument(upload, "-authenticationKeyPath", run.keyPath.absolutePathString())
            assertArgument(upload, "-archivePath", archive.argumentValue("-archivePath"))

            val exportOptions = Path(upload.argumentValue("-exportOptionsPlist")).readText()
            assertPlistEntry(exportOptions, "method", "<string>app-store-connect</string>")
            assertPlistEntry(exportOptions, "destination", "<string>upload</string>")
            assertPlistEntry(exportOptions, "teamID", "<string>$TEAM_ID</string>")

            assertFalse((run.moduleDir / AUTH_PROPERTIES_FILE_NAME).exists(), "Credentials from env must not be saved")
        }
    }

    @Test
    fun `new bundle ID is registered`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { app(bundleId = BUNDLE_ID, name = APP_NAME) }) { asc ->
            runPublishTestflight(asc)

            val creation = asc.requests.single { it.method == "POST" && it.path == "/v1/bundleIds" }
            assertContains(creation.body, "\"identifier\":\"$BUNDLE_ID\"")
            assertContains(creation.body, "\"name\":\"$APP_NAME\"")
            assertContains(creation.body, "\"platform\":\"IOS\"")
            asc.assertAllRequestsAuthorized()
            asc.assertNoUnexpectedRequests()
        }
    }

    @Test
    fun `team is inferred from the bundle ID and saved into the project`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(asc, modifyProject = { it.editPbxproj { replace(TEAM_SETTING, "") } })

            run.result.assertOutputContains("Xcode project didn't have any `team ID` set up.")
            assertContains(run.pbxproj().readText(), TEAM_SETTING)
        }
    }

    @Test
    fun `credentials are read from the auth properties file`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(
                asc = asc,
                credentialsInEnv = false,
                modifyProject = { projectDir ->
                    val keyPath = key.writeP8(projectDir / "AuthKey.p8")
                    (projectDir / AUTH_PROPERTIES_FILE_NAME).writeText(
                        "keyId=${key.keyId}\nissuerId=${key.issuerId}\nkeyPath=${keyPath.absolutePathString()}\n"
                    )
                },
            )
            run.result.assertOutputContains("is ready!")
            asc.assertAllRequestsAuthorized()
        }
    }

    @Test
    fun `credentials from env take precedence over the auth properties file`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(
                asc = asc,
                modifyProject = { projectDir ->
                    val staleKey = AscTestKey(keyId = "ZZZZZ99999")
                    val staleKeyPath = staleKey.writeP8(projectDir / "StaleAuthKey.p8")
                    (projectDir / AUTH_PROPERTIES_FILE_NAME).writeText(
                        "keyId=${staleKey.keyId}\nissuerId=${staleKey.issuerId}\nkeyPath=${staleKeyPath.absolutePathString()}\n"
                    )
                },
            )
            run.result.assertOutputContains("is ready!")
            asc.assertAllRequestsAuthorized()
        }
    }

    @Test
    fun `credentials from env and the auth properties file are not mixed`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val keyPath = key.writeP8(tempRoot / "PartialAuthKey.p8")
            val run = runPublishTestflight(
                asc = asc,
                credentialsInEnv = false,
                extraEnv = mapOf("KOTLIN_TOOLCHAIN_ASC_KEY_PATH" to keyPath.absolutePathString()),
                expectedExitCode = 1,
                modifyProject = { projectDir ->
                    (projectDir / AUTH_PROPERTIES_FILE_NAME).writeText("keyId=${key.keyId}\nissuerId=${key.issuerId}\n")
                },
            )
            run.result.assertOutputContains("Connect your Apple Developer account")
            assertEquals([], asc.requests)
            run.assertNotUploaded()
        }
    }

    @Test
    fun `failed build processing is reported`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp(); buildStates("FAILED") }) { asc ->
            val run = runPublishTestflight(asc)
            run.result.assertOutputContains("Apple couldn't process this build (state: `FAILED`)")
        }
    }

    @Test
    fun `build upload lookup failure is reported`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp(); failBuildUploadLookup() }) { asc ->
            val run = runPublishTestflight(asc)
            run.result.assertOutputContains("The upload is complete, but I couldn't check its processing status")
        }
    }

    @Test
    fun `waits until the build is processed, surviving transient errors`() = runSlowTest {
        // The server error and the dropped connection are retried by the CLI's HTTP client within the second poll
        withFakeAppStoreConnect(key, configure = {
            existingBundleIdAndApp()
            buildStates("PROCESSING", 503, DropConnection, "VALID")
        }) { asc ->
            val run = runPublishTestflight(asc)
            run.result.assertOutputContains("is ready!")
            val buildLookups = asc.requests.count { it.path == "/v1/builds" && it.query.containsKey("filter[version]") }
            assertEquals(4, buildLookups, "Unexpected number of build lookups")
        }
    }

    @Test
    fun `persistent network failure while waiting for the build is reported`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp(); buildStates(DropConnection) }) { asc ->
            val run = runPublishTestflight(asc)
            run.result.assertOutputContains("has been uploaded!")
            run.result.assertOutputContains("The upload is complete, but I couldn't check its processing status")
            run.result.assertOutputContains("Check your network connection.")
        }
    }

    // region Wizard fallbacks

    @Test
    fun `missing credentials stop the setup`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(asc, credentialsInEnv = false, expectedExitCode = 1)
            run.result.assertOutputContains("Connect your Apple Developer account")
            run.result.assertOutputContains("A readable private key path, Key ID, and Issuer ID are all required.")
            assertEquals([], asc.requests)
            run.assertNotUploaded()
        }
    }

    @Test
    fun `manual signing stops the setup`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(
                asc = asc,
                expectedExitCode = 1,
                modifyProject = { it.editPbxproj { replace("CODE_SIGN_STYLE = Automatic;", "CODE_SIGN_STYLE = Manual;") } },
            )
            run.result.assertOutputContains("Automatic signing is required")
            run.result.assertOutputContains("Enable automatic signing in Xcode's Signing & Capabilities")
            run.assertPbxprojUnchanged()
            run.assertNotUploaded()
        }
    }

    @Test
    fun `placeholder bundle ID stops the setup`() = runSlowTest {
        withFakeAppStoreConnect(key) { asc ->
            val run = runPublishTestflight(
                asc = asc,
                expectedExitCode = 1,
                modifyProject = { it.editPbxproj { replace(BUNDLE_ID, "com.example.app") } },
            )
            run.result.assertOutputContains("Choose your app's bundle ID")
            run.result.assertOutputContains("A unique, non-placeholder `PRODUCT_BUNDLE_IDENTIFIER` is required")
            run.assertPbxprojUnchanged()
            run.assertNotUploaded()
        }
    }

    @Test
    fun `bundle ID conflict stops the setup`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { conflictOnCreate(BUNDLE_ID) }) { asc ->
            val run = runPublishTestflight(asc, expectedExitCode = 1)
            run.result.assertOutputContains("Bundle ID unavailable")
            run.result.assertOutputContains("The bundle ID `$BUNDLE_ID` could not be registered.")
            run.assertPbxprojUnchanged()
            run.assertNotUploaded()
        }
    }

    @Test
    fun `team mismatch stops the setup`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = {
            bundleId(BUNDLE_ID, seedId = "Z9Y8X7W6V5")
            app(bundleId = BUNDLE_ID, name = APP_NAME)
        }) { asc ->
            val run = runPublishTestflight(asc, expectedExitCode = 1)
            run.result.assertOutputContains("Developer teams differ between the token and the project")
            run.result.assertOutputContains("`DEVELOPMENT_TEAM` must match the API key's team (`Z9Y8X7W6V5`).")
            run.assertPbxprojUnchanged()
            run.assertNotUploaded()
        }
    }

    @Test
    fun `missing app record stops the setup`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { bundleId(BUNDLE_ID) }) { asc ->
            val run = runPublishTestflight(asc, expectedExitCode = 1)
            run.result.assertOutputContains("Create the App Store Connect app record")
            run.result.assertOutputContains("SKU")
            run.result.assertOutputContains(
                "Create the App Store Connect app record for '$BUNDLE_ID' using the instructions above."
            )
            run.assertNotUploaded()
        }
    }

    @Test
    fun `missing encryption declaration is only a warning`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(
                asc = asc,
                modifyProject = { projectDir ->
                    val plist = projectDir / "info.plist"
                    plist.writeText(plist.readText().replace(ENCRYPTION_PLIST_ENTRY, ""))
                },
            )
            run.result.assertOutputContains("The app doesn't declare whether it uses non-exempt encryption")
            run.result.assertOutputContains("Missing Compliance")
            run.result.assertOutputContains("is ready!")
            assertFalse(run.infoPlist().readText().contains("ITSAppUsesNonExemptEncryption"))
        }
    }

    @Test
    fun `unauthorized App Store Connect access stops the setup`() = runSlowTest {
        // The server knows another key with the same ID, e.g. because the key was revoked and recreated
        withFakeAppStoreConnect(AscTestKey(), configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(asc, expectedExitCode = 1)
            run.result.assertOutputContains(
                "Unable to list the bundle IDs in App Store Connect: Provide a properly configured and signed bearer token"
            )
            run.result.assertOutputContains(
                "Make sure the App Store Connect API key, key ID, and issuer ID are valid and the key has not been revoked."
            )
            run.result.assertOutputContains("Resolve the error and rerun this command later.")
            assertTrue(asc.requests.all { it.authError != null }, "All requests must be rejected: ${asc.requests}")
            run.assertNotUploaded()
        }
    }

    @Test
    fun `network failure stops the setup`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { dropConnections("GET", "/v1/bundleIds") }) { asc ->
            val run = runPublishTestflight(asc, expectedExitCode = 1)
            run.result.assertOutputContains("Unable to list the bundle IDs in App Store Connect")
            run.result.assertOutputContains("Resolve the error and rerun this command later.")
            run.assertNotUploaded()
        }
    }

    @Test
    fun `App Store Connect server error stops the setup`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { bundleId(BUNDLE_ID); failRoute("GET", "/v1/apps", 500) }) { asc ->
            val run = runPublishTestflight(asc, expectedExitCode = 1)
            run.result.assertOutputContains(
                "Unable to look up the app record for the bundle ID '$BUNDLE_ID' in App Store Connect: " +
                        "An unexpected error occurred on the server side."
            )
            run.result.assertOutputContains("App Store Connect is having issues")
            run.result.assertOutputContains("Resolve the error and rerun this command later.")
            run.assertNotUploaded()
        }
    }

    // endregion

    // region xcodebuild failures

    @Test
    fun `archive failure is reported`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(asc, mode = FakeXcodebuildMode.ArchiveFail, expectedExitCode = 1)
            run.result.assertOutputContains("xcodebuild archive failed with exit code 65")
            assertFalse(run.xcodebuildInvocations.any { "-exportArchive" in it })
            assertEquals([], asc.uploads)
        }
    }

    @Test
    fun `upload failure is reported`() = runSlowTest {
        withFakeAppStoreConnect(key, configure = { existingBundleIdAndApp() }) { asc ->
            val run = runPublishTestflight(asc, mode = FakeXcodebuildMode.UploadFail, expectedExitCode = 1)
            run.result.assertOutputContains("uploading the Xcode archive to App Store Connect failed with exit code 70")
            assertEquals([], asc.uploads)
        }
    }

    // endregion

    private class PublishRun(
        val result: AmperCliResult,
        val moduleDir: Path,
        val keyPath: Path,
        val xcodebuildInvocations: List<List<String>>,
        val originalPbxproj: String,
    ) {
        fun pbxproj(): Path = moduleDir / "module.xcodeproj/project.pbxproj"
        fun infoPlist(): Path = moduleDir / "info.plist"

        fun assertPbxprojUnchanged() {
            assertEquals(originalPbxproj, pbxproj().readText(), "The Xcode project must not be modified")
        }

        fun assertNotUploaded() {
            assertEquals([], xcodebuildInvocations.filter { "archive" in it || "-exportArchive" in it })
        }
    }

    private suspend fun runPublishTestflight(
        asc: FakeAppStoreConnect,
        mode: FakeXcodebuildMode = FakeXcodebuildMode.Ok,
        credentialsInEnv: Boolean = true,
        extraEnv: Map<String, String> = emptyMap(),
        expectedExitCode: Int = 0,
        modifyProject: (Path) -> Unit = {},
    ): PublishRun {
        val projectDir = testProject(TEST_PROJECT)
        modifyProject(projectDir)
        val originalPbxproj = (projectDir / "module.xcodeproj/project.pbxproj").readText()
        val keyPath = key.writeP8(tempRoot / "AuthKey_${key.keyId}.p8")
        val log = tempRoot / "xcodebuild-${System.nanoTime()}.log"
        val result = runCli(
            projectDir = projectDir,
            "publish", "testflight",
            expectedExitCode = expectedExitCode,
            assertEmptyStdErr = false,
            configureEnvironment = {
                put("KOTLIN_TOOLCHAIN_ASC_API_URL", asc.baseUrl)
                if (credentialsInEnv) {
                    put("KOTLIN_TOOLCHAIN_ASC_KEY_ID", key.keyId)
                    put("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID", key.issuerId)
                    put("KOTLIN_TOOLCHAIN_ASC_KEY_PATH", keyPath.absolutePathString())
                } else {
                    remove("KOTLIN_TOOLCHAIN_ASC_KEY_ID")
                    remove("KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID")
                    remove("KOTLIN_TOOLCHAIN_ASC_KEY_PATH")
                }
                putAll(extraEnv)
                FakeXcode.configureEnvironment(this, ascUrl = asc.baseUrl, log = log, mode = mode, bundleId = BUNDLE_ID)
            },
        )
        return PublishRun(
            result = result,
            moduleDir = projectDir,
            keyPath = keyPath,
            xcodebuildInvocations = FakeXcode.readInvocations(log),
            originalPbxproj = originalPbxproj,
        )
    }

    private fun FakeAscState.existingBundleIdAndApp() {
        bundleId(BUNDLE_ID, seedId = TEAM_ID)
        app(bundleId = BUNDLE_ID, name = APP_NAME)
    }

    private fun FakeAppStoreConnect.assertAllRequestsAuthorized() {
        assertTrue(requests.isNotEmpty(), "Some App Store Connect requests are expected")
        requests.forEach { assertNull(it.authError, "Request ${it.method} ${it.path} is not authorized") }
    }

    private fun FakeAppStoreConnect.assertNoUnexpectedRequests() {
        assertEquals([], unexpectedRequests, "Unexpected App Store Connect requests")
    }

    private fun Path.editPbxproj(edit: String.() -> String) {
        val pbxproj = this / "module.xcodeproj/project.pbxproj"
        pbxproj.writeText(pbxproj.readText().edit())
    }

    /**
     * Asserts that stdout or stderr contains the given [text].
     * Inline code markers (backticks) are ignored, because the CLI renders Markdown.
     */
    private fun AmperCliResult.assertOutputContains(text: String) {
        val output = (stdout + "\n" + stderr).filterAnsiCodes().replace("`", "")
        if (text.replace("`", "") !in output) {
            fail("Expected the output to contain:\n$text\n\nActual stdout:\n$stdout\n\nActual stderr:\n$stderr")
        }
    }

    private fun String.filterAnsiCodes(): String = replace(ANSI_CODE_REGEX, "")

    private fun assertArgument(args: List<String>, name: String, expectedValue: String) {
        assertEquals(expectedValue, args.argumentValue(name), "Unexpected value of $name in $args")
    }

    private fun List<String>.argumentValue(name: String): String {
        val index = indexOf(name)
        if (index < 0 || index == lastIndex) fail("Argument $name is missing in $this")
        return this[index + 1]
    }

    private fun assertPlistEntry(plist: String, key: String, valueXml: String) {
        val entry = Regex("<key>${Regex.escape(key)}</key>\\s*${Regex.escape(valueXml)}")
        assertTrue(entry.containsMatchIn(plist), "Expected $key=$valueXml in:\n$plist")
    }

    private companion object {
        const val TEST_PROJECT = "ios/testflight-app"
        const val BUNDLE_ID = "org.jetbrains.ktc.testflight"
        const val APP_NAME = "TestflightApp"
        const val TEAM_ID = "A1B2C3D4E5"
        const val TEAM_SETTING = "DEVELOPMENT_TEAM = $TEAM_ID;"
        const val AUTH_PROPERTIES_FILE_NAME = "app-store-connect-auth.properties"
        const val ENCRYPTION_PLIST_ENTRY = "\t<key>ITSAppUsesNonExemptEncryption</key>\n\t<false/>\n"
        val ANSI_CODE_REGEX = Regex("\u001B\\[[;\\d]*[A-Za-z]|\u001B]8;[^\u0007\u001B]*(\u0007|\u001B\\\\)")
    }
}
