/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.publish.ios

import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.muted
import com.github.ajalt.mordant.terminal.warning
import com.jetbrains.cidr.xcode.plist.XMLPlistDriver
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.amper.appstoreconnect.AppStoreConnectApp
import org.jetbrains.amper.appstoreconnect.AppStoreConnectAuthKey
import org.jetbrains.amper.appstoreconnect.AppStoreConnectBuildUpload
import org.jetbrains.amper.appstoreconnect.AppStoreConnectBundleId
import org.jetbrains.amper.appstoreconnect.AppStoreConnectException
import org.jetbrains.amper.appstoreconnect.createBundleId
import org.jetbrains.amper.appstoreconnect.findApp
import org.jetbrains.amper.appstoreconnect.findBuild
import org.jetbrains.amper.appstoreconnect.getBuildUpload
import org.jetbrains.amper.appstoreconnect.listBundleIds
import org.jetbrains.amper.appstoreconnect.obtainTeamId
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.terminal.printCompletedMilestone
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.widgets.status.standaloneOperationProgressWidget
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.tasks.IosPublishSettings
import org.jetbrains.amper.tasks.IpaPackageSettings
import org.jetbrains.amper.tasks.PackageSettings
import org.jetbrains.amper.tasks.PublishSettings
import org.jetbrains.amper.tasks.getTaskName
import org.jetbrains.amper.tasks.ios.IosPublishToTestflightTask
import org.jetbrains.amper.tasks.ios.IosTaskType
import org.jetbrains.amper.tasks.ios.XcodeBuildSettingsResolution
import org.jetbrains.amper.tasks.ios.developmentTeam
import org.jetbrains.amper.tasks.ios.getXcodeEnvironment
import org.jetbrains.amper.tasks.ios.toPlist
import org.jetbrains.amper.util.BuildType
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

internal suspend fun publishIosApp(module: AmperModule, cliContext: ProjectCliContext, model: Model) {
    context(cliContext.terminal) {
        val (publication, packageSettings) = try {
            standaloneOperationProgressWidget(cliContext.terminal) {
                preparePublication(module, cliContext)
            }
        } catch (e: AppStoreConnectException) {
            userReadableError("${e.message}\nResolve the error and rerun this command later.", cause = e)
        }
        val uploadResult = withBackend(
            cliContext = cliContext,
            model = model,
            packageSettings = packageSettings,
            publishSettings = PublishSettings(ios = publication),
        ) { backend ->
            val task = IosTaskType.PublishToTestflight.getTaskName(
                module = module,
                platform = Platform.IOS_ARM64,
                isTest = false,
                buildType = BuildType.Release,
            )
            backend.runTask(task.id)
        } as IosPublishToTestflightTask.Result

        epilogue(
            uploadResult = uploadResult,
            publication = publication,
            testFlightUrl = "https://appstoreconnect.apple.com/apps/${publication.app.id}/testflight",
        )
    }
}

context(terminal: Terminal)
private suspend fun epilogue(
    uploadResult: IosPublishToTestflightTask.Result,
    publication: IosPublishSettings,
    testFlightUrl: String,
) {
    val uploadedMessage = Markdown(
        """
           |
           |✅ **${publication.app.name} has been uploaded!** 🚀
           |
           |Apple is processing the build now. This usually takes a few minutes, but in rare cases can take longer ⏳
        """.trimMargin()
    )
    terminal.println(terminal.theme.success(terminal.render(uploadedMessage)))
    terminal.println(
        Markdown(
            """
               |What's next:
               |
               |- **Test the build:** add testers (including yourself) on the [TestFlight page]($testFlightUrl), 
               |  then install it with the [TestFlight app](https://apps.apple.com/app/testflight/id899247664). 
               |  Testers outside your team need a [Beta App Review](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers/) first.
               |- **Release it in the App Store:** complete the store listing (metadata, screenshots, privacy details), 
               |  select this build, and submit it for [App Review](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-app/) 
               |  in [App Store Connect](https://appstoreconnect.apple.com/apps/${publication.app.id}/distribution).
            """.trimMargin()
        )
    )

    val waitResult = standaloneOperationProgressWidget(terminal) {
        operationEventScope("Waiting for the build to become available in TestFlight") {
            terminal.muted("Note: It's safe to interrupt the process now (if you don't need to know exactly when the build becomes available)")
            waitForTestflightBuild(
                authKey = publication.authKey,
                appId = publication.app.id,
                uploadId = uploadResult.uploadId,
            )
        }
    }
    terminal.println(
        Markdown(
            when (waitResult) {
                TestflightBuildWaitResult.Ready ->
                    "🎉 **`${publication.app.name}` is ready!** You can now test it via [TestFlight]($testFlightUrl) " +
                            "or [submit it for App Review](https://appstoreconnect.apple.com/apps/${publication.app.id}/distribution)."
                is TestflightBuildWaitResult.Failed ->
                    "⚠️ Apple couldn't process this build (state: `${waitResult.processingState}`). See the build " +
                            "details in [TestFlight]($testFlightUrl) for the errors, then upload a fixed build."
                TestflightBuildWaitResult.TimedOut ->
                    "⏳ Apple is still processing the build. Check [TestFlight]($testFlightUrl) again later."
                is TestflightBuildWaitResult.LookupFailed ->
                    "✅ The upload is complete, but I couldn't check its processing status: `${waitResult.message}`. " +
                            "Check [TestFlight]($testFlightUrl) again later."
            }
        )
    )
}

/**
 * Polls App Store Connect until the build of the upload with the given [uploadId] is processed.
 *
 * The state of the build upload itself isn't checked: `xcodebuild -exportArchive` with the `upload` destination only
 * succeeds (and records a successful distribution in the archive) if Apple accepted the upload, so from here on
 * only the processing of the build can fail.
 *
 * Failed requests are not retried here: the HTTP client already retries server errors, network errors, and timeouts.
 * A request that still fails ends the wait, because the upload itself is already complete.
 */
private suspend fun waitForTestflightBuild(
    authKey: AppStoreConnectAuthKey,
    appId: String,
    uploadId: String,
): TestflightBuildWaitResult {
    val result = withTimeoutOrNull(1.hours) {
        var outcome: TestflightBuildWaitResult? = null
        var uploadDetails: AppStoreConnectBuildUpload? = null
        while (outcome == null) {
            try {
                val build = run {
                    val (version, buildNumber) = uploadDetails
                        ?: authKey.getBuildUpload(uploadId).also { uploadDetails = it }
                    authKey.findBuild(appId, version, buildNumber)
                }
                outcome = build?.processingState?.toTerminalWaitResult()
            } catch (e: AppStoreConnectException) {
                outcome = TestflightBuildWaitResult.LookupFailed(e.message ?: "App Store Connect request failed")
            }
            if (outcome == null) delay(30.seconds)
        }
        outcome
    }
    return result ?: TestflightBuildWaitResult.TimedOut
}

private fun String.toTerminalWaitResult(): TestflightBuildWaitResult? = when (uppercase()) {
    "VALID" -> TestflightBuildWaitResult.Ready
    "FAILED", "INVALID" -> TestflightBuildWaitResult.Failed(this)
    else -> null
}

private sealed interface TestflightBuildWaitResult {
    data object Ready : TestflightBuildWaitResult
    data class Failed(val processingState: String) : TestflightBuildWaitResult
    data object TimedOut : TestflightBuildWaitResult
    data class LookupFailed(val message: String) : TestflightBuildWaitResult
}

context(_: OperationEventSink, terminal: Terminal)
private suspend fun preparePublication(
    module: AmperModule,
    cliContext: ProjectCliContext,
): PreparedIosPublication {
    val environment = operationEventScope("Checking Xcode") {
        context(cliContext.problemReporter) {
            getXcodeEnvironment(cliContext.processRunner)
        }
    }
    val project = context(environment) { XcodeBuildSettingsResolution.load(module, BuildType.Release) }
    val authKey = setupMilestone("App Store Connect access") {
        obtainAuthKey(module.source.moduleDir / "app-store-connect-auth.properties")
    }
    setupMilestone("Automatic signing") {
        prepareSigning(project)
    }
    val appName = project.getSetting("PRODUCT_NAME")?.takeIf(String::isNotBlank)
        ?: userReadableError("PRODUCT_NAME is missing from the Release configuration. Set it in Xcode and rerun this command.")
    val bundleId = setupMilestone("Bundle ID") {
        ensureBundleId(authKey, project, appName).also {
            alignPlistBundleId(module, project, it.identifier)
        }
    }
    val teamId = setupMilestone("Team ID") {
        resolveTeamId(authKey, bundleId, project)
    }
    val app = setupMilestone("Application record") {
        ensureAppRecord(authKey, bundleId, appName, teamId)
    }
    setupMilestone("Encryption declaration") {
        prepareEncryption(module, project)
    }
    terminal.printCompletedMilestone("Setup is done")
    val exportOptionsPlistPath = writeExportOptions(module, cliContext, teamId)
    return PreparedIosPublication(
        publication = IosPublishSettings(module, authKey, app, exportOptionsPlistPath),
        packageSettings = PackageSettings(
            ipa = IpaPackageSettings(
                authKeyProvider = { authKey },
            ),
        ),
    )
}

private class PreparedIosPublication(
    val publication: IosPublishSettings,
    val packageSettings: PackageSettings,
)

context(_: OperationEventSink, terminal: Terminal)
private suspend fun ensureBundleId(
    authKey: AppStoreConnectAuthKey,
    project: XcodeBuildSettingsResolution.FromModel.Resolver,
    appName: String,
): AppStoreConnectBundleId {
    var identifier = project.getSetting("PRODUCT_BUNDLE_IDENTIFIER").orEmpty()
    while (true) {
        if (identifier.isBlank() || identifier.startsWith("com.example") || !BundleIdPattern.matches(identifier)) {
            identifier = setupRequiresInput(
                title = "Choose your app's bundle ID",
                instructions = "**Enter a unique bundle ID**, e.g. `com.yourcompany.yourapp`. " +
                        "`${identifier.ifBlank { "(empty)" }}` is a placeholder or invalid.\n\n" +
                        "*Use a domain you control. Each answer is saved as `PRODUCT_BUNDLE_IDENTIFIER` for `Release`.*",
                fallback = "A unique, non-placeholder `PRODUCT_BUNDLE_IDENTIFIER` is required in the Xcode project.",
                block = { ask("Bundle ID") },
            )
            project.setSetting("PRODUCT_BUNDLE_IDENTIFIER", identifier)
            continue
        }
        try {
            return operationEventScope("Registering the bundle ID") {
                authKey.listBundleIds().find { it.identifier == identifier }
                    ?: authKey.createBundleId(identifier, bundleIdName(appName))
            }
        } catch (e: AppStoreConnectException) {
            if (e.status != HttpStatusCode.Conflict) throw e
            identifier = setupRequiresInput(
                title = "Bundle ID unavailable",
                instructions = "**Choose another bundle ID.** `$identifier` could not be registered.\n\n" +
                        "*It may belong to another team/app.*",
                fallback = "The bundle ID `$identifier` could not be registered. Set a unique `PRODUCT_BUNDLE_IDENTIFIER`.",
                block = { ask("New bundle ID") },
            )
            project.setSetting("PRODUCT_BUNDLE_IDENTIFIER", identifier)
        }
    }
}

private fun bundleIdName(productName: String): String = productName
    .replace("[^A-Za-z0-9 ]".toRegex(), " ")
    .replace(" +".toRegex(), " ").trim().ifEmpty { "App" }

context(_: OperationEventSink, terminal: Terminal)
private suspend fun resolveTeamId(
    authKey: AppStoreConnectAuthKey,
    bundleId: AppStoreConnectBundleId,
    project: XcodeBuildSettingsResolution.FromModel.Resolver,
): String {
    // The App ID prefix (seed ID) of the bundle ID is used as the team ID first. It can differ from the team ID for
    // bundle IDs registered long ago with a legacy App ID prefix, but it's the team ID for all new bundle IDs.
    // We prioritize new users here, so it's an acceptable trade-off.
    val inferredTeamId = operationEventScope("Determining the developer team") {
        bundleId.seedId ?: authKey.obtainTeamId()
    }
    val configuredTeam = project.developmentTeam?.takeIf(String::isNotBlank)
    val teamId =
        inferredTeamId ?: configuredTeam ?: /*TODO: I don't think it's worth it to support this fallback */ setupRequiresInput(
            title = "Apple Developer Team ID",
            instructions = "**Enter your API key's Team ID** from " +
                    "[Apple Developer → Membership details](https://developer.apple.com/account).\n\n" +
                    "*This is not the Issuer ID or Key ID.*",
            fallback = "Set `DEVELOPMENT_TEAM` for `Release` in Xcode.",
        ) {
            var answer = ask("Team ID")
            while (!TeamIdPattern.matches(answer)) {
                message("A Team ID contains **10 uppercase letters and digits**, for example `A1B2C3D4E5`.")
                answer = ask("Team ID")
            }
            answer
        }
    if (teamId != configuredTeam) {
        if (configuredTeam == null) {
            terminal.warning("Xcode project didn't have any `team ID` set up. " +
                    "Saving the team inferred from the auth token (`$teamId`) into the project")
        } else {
            setupRequiresInput(
                title = "Developer teams differ between the token and the project",
                instructions = """
                    Xcode project currently uses a development team with the ID `$configuredTeam`.
                    The team ID inferred from the Auth Token is `$teamId`.
                    Building the app for one team and publishing for another usually leads to signing conflicts and errors.

                    So you either use a wrong `.p8` Auth Token or the project's team is wrong.
                    
                    *We can update the project to use the team associated with the token; or you can abort the flow
                    and provide a different Apple Store Connect token that matches the project's team.* 
                """.trimIndent(),
                fallback = "`DEVELOPMENT_TEAM` must match the API key's team (`$teamId`). No change was made.",
                block = { confirmOrQuit("Use the API key's team and update the Xcode project?") },
            )
        }
        project.setSetting("DEVELOPMENT_TEAM", teamId)
    }
    return teamId
}

context(_: OperationEventSink, terminal: Terminal)
private suspend fun ensureAppRecord(
    authKey: AppStoreConnectAuthKey,
    bundleId: AppStoreConnectBundleId,
    appName: String,
    teamId: String,
): AppStoreConnectApp {
    val existing = operationEventScope("Looking up the app record") { authKey.findApp(bundleId.identifier) }
    if (existing != null) return existing
    terminal.println(Markdown(
        "**Create an app** in [App Store Connect → Apps](https://appstoreconnect.apple.com/apps): " +
                "select team `$teamId`, then **+ → New App**. Use these values:"
    ))
    terminal.println(table {
        header { row(Markdown("**New App field**"), Markdown("**Suggested value**")) }
        body {
            row("Platforms", "iOS")
            row("Name", Markdown("`$appName` (if available)"))
            row("Primary Language", "Your app's main language")
            row("Bundle ID", Markdown("${bundleId.name} — `${bundleId.identifier}`"))
            row("SKU", Markdown("`${bundleId.identifier}`"))
            row("User Access", "Full Access")
        }
    })
    return setupRequiresInput(
        title = "Create the App Store Connect app record",
        instructions = "Click **Create**, then confirm below.\n\n" +
                "*Requires Account Holder, Admin, or App Manager access. If unavailable, check " +
                "[agreements](https://appstoreconnect.apple.com/business) with the Account Holder.*",
        fallback = "Create the App Store Connect app record for '${bundleId.identifier}' using the instructions above.",
    ) {
        var app: AppStoreConnectApp? = null
        while (app == null) {
            confirmOrQuit("Confirm when you created the app record")
            app = authKey.findApp(bundleId.identifier)
            if (app == null) message(
                "**Not found.** Check the team and bundle ID, click **Create**, and wait a minute before retrying."
            )
        }
        app
    }
}

context(_: OperationEventSink, terminal: Terminal)
internal suspend fun prepareSigning(project: XcodeBuildSettingsResolution.FromModel.Resolver) {
    val automaticSigningSettings = mapOf(
        "CODE_SIGN_STYLE" to "Automatic",
        "CODE_SIGNING_ALLOWED" to "YES",
        "PROVISIONING_PROFILE_SPECIFIER" to "",
        "PROVISIONING_PROFILE" to "",
    )
    if (!project.getSetting("CODE_SIGN_STYLE").equals("Automatic", ignoreCase = true) ||
        project.getSetting("CODE_SIGNING_ALLOWED").equals("NO", ignoreCase = true) ||
        !project.getSetting("PROVISIONING_PROFILE_SPECIFIER").isNullOrBlank() ||
        !project.getSetting("PROVISIONING_PROFILE").isNullOrBlank() ||
        automaticSigningSettings.keys.any(project::hasQualifiedSetting)
    ) {
        setupRequiresInput(
            title = "Automatic signing is required",
            instructions = """
                `kotlin publish` relies on *Automatic Signing* in Xcode. Manual signing scenarios are not covered at the moment.

                *WARNING:* If you have a manual signing scenario setup, it's recommended to use other publishing flows,
                e.g. [`fastlane`](https://docs.fastlane.tools/getting-started/ios/setup/).

                This clears manual provisioning profiles and qualified signing overrides in the target's `Release` configuration.
            """.trimIndent(),
            fallback = "Enable automatic signing in Xcode's Signing & Capabilities for the Release configuration.",
        ) {
            confirmOrQuit("Enable automatic signing for Release?", default = null)
            project.setSettingsWithoutQualifiers(automaticSigningSettings)
        }
    }
}

private fun writeExportOptions(
    module: AmperModule,
    cliContext: ProjectCliContext,
    teamId: String,
): Path {
    val exportOptions = (cliContext.projectTempRoot.path / module.userReadableName / "testflight-export-options.plist")
        .createParentDirectories()
    XMLPlistDriver().write(
        mapOf(
            "method" to "app-store-connect",
            "destination" to "upload",
            "teamID" to teamId,
            "signingStyle" to "automatic",
            "manageAppVersionAndBuildNumber" to true,
            "uploadSymbols" to true,
        ).toPlist(), exportOptions.toFile()
    )
    return exportOptions
}

private val BundleIdPattern = "[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+".toRegex()
private val TeamIdPattern = "[A-Z0-9]{10}".toRegex()
