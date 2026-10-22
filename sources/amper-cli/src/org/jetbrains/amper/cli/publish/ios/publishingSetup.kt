/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.publish.ios

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.warning
import com.jetbrains.cidr.xcode.plist.Plist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.appstoreconnect.AppStoreConnectAuthKey
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV
import org.jetbrains.amper.tasks.KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV
import org.jetbrains.amper.tasks.KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV
import org.jetbrains.amper.tasks.ascApiUrl
import org.jetbrains.amper.tasks.ios.XcodeBuildSettingsResolution
import org.jetbrains.amper.tasks.ios.infoPlistPath
import org.jetbrains.amper.tasks.ios.readPlist
import org.jetbrains.amper.tasks.ios.writePlist
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Obtains the App Store Connect API key credentials.
 *
 * Complete and valid credentials from the environment variables take precedence over the ones saved in
 * [authPropertiesPath]. The values from both sources are never mixed. If neither source is complete, the user is asked
 * to enter the credentials, which are then saved to [authPropertiesPath].
 */
context(_: OperationEventSink, terminal: Terminal)
internal suspend fun obtainAuthKey(authPropertiesPath: Path): AppStoreConnectAuthKey {
    val environment = AuthFields.associateWith { System.getenv(it.environmentVariable)?.trim() }
    val saved = withContext(Dispatchers.IO) { readSavedAuthValues(authPropertiesPath) }
    val existing = authKeyFrom(environment) ?: authKeyFrom(saved)
    if (existing != null) return existing
    val values = if (authPropertiesPath.exists()) saved else environment
    val authKey = setupRequiresInput(
        title = "Connect your Apple Developer account",
        instructions = """
            1. Get a team API key from [App Store Connect → Users and Access → Integrations](https://appstoreconnect.apple.com/access/integrations/api).
               *Use an Admin team key for automatic provisioning. Apple Developer Program membership and API access are required.*
            2. Download the `.p8` key file somewhere safe (do not include it into the VCS) and then provide its path, Key ID, and Issuer ID:
              - **Private key path:** path to the downloaded `.p8` file *(Kotlin Toolchain never copies/stores this file)*.
              - **Key ID:** *(shown next to the key)*.
              - **Issuer ID:** *(shown above the team's keys)*.

            Entered values are saved to `${authPropertiesPath.fileName}` in the module directory; an entry for it will be added to the .gitignore.

            *Alternatively, set `$KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV`, `$KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV`, and `$KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV`.*
        """.trimIndent(),
        fallback = "A readable private key path, Key ID, and Issuer ID are all required. " +
                "Set `$KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV`, `$KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV`, and " +
                "`$KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV`, or complete setup interactively.",
    ) {
        val inputs = values.toMutableMap()
        while (authKeyFrom(inputs) == null) {
            for (field in AuthFields) {
                if (!validAuthValue(field, inputs[field])) {
                    inputs[field] = ask(field.label)
                    if (!validAuthValue(field, inputs[field])) {
                        message(
                            "**Invalid value.** Check the ${
                                if (field == AuthField.KeyPath)
                                    "path points to a readable regular .p8 file" else "value copied from App Store Connect"
                            }."
                        )
                    }
                }
            }
        }
        authKeyFrom(inputs) ?: error("Validated credentials are missing")
    }
    val properties = Properties().apply {
        setProperty(AuthField.KeyId.propertyName, authKey.keyId)
        setProperty(AuthField.IssuerId.propertyName, authKey.issuerId)
        setProperty(AuthField.KeyPath.propertyName, authKey.keyPath.toString())
    }
    withContext(Dispatchers.IO) {
        ensureIgnored(authPropertiesPath)
        authPropertiesPath.outputStream().use {
            properties.store(it, "App Store Connect authentication (no private key contents; do not commit)")
        }
    }
    return authKey
}

private fun readSavedAuthValues(authPropertiesPath: Path): Map<AuthField, String?> {
    val properties = Properties()
    if (authPropertiesPath.exists()) authPropertiesPath.inputStream().use(properties::load)
    return AuthFields.associateWith { properties.getProperty(it.propertyName)?.trim() }
}

private fun ensureIgnored(authPropertiesPath: Path) {
    val gitignorePath = authPropertiesPath.resolveSibling(".gitignore")
    val entry = authPropertiesPath.fileName.toString()
    val contents = if (gitignorePath.exists()) gitignorePath.readText() else ""
    if (contents.lineSequence().any { it.trim() == entry }) return

    val separator = if (contents.isEmpty() || contents.endsWith('\n')) "" else "\n"
    gitignorePath.writeText("$contents$separator$entry\n")
}

private fun authKeyFrom(values: Map<AuthField, String?>): AppStoreConnectAuthKey? {
    if (!AuthFields.all { validAuthValue(it, values[it]) }) return null
    return AppStoreConnectAuthKey(
        keyId = values.getValue(AuthField.KeyId)!!,
        issuerId = values.getValue(AuthField.IssuerId)!!,
        keyPath = Path(values.getValue(AuthField.KeyPath)!!).absolute(),
        apiUrl = ascApiUrl(),
    )
}

private fun validAuthValue(field: AuthField, value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return when (field) {
        AuthField.KeyId -> "[A-Z0-9]{10}".toRegex().matches(value)
        AuthField.IssuerId ->
            "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}".toRegex().matches(value)
        AuthField.KeyPath -> try {
            val path = Path(value)
            path.isRegularFile() && path.isReadable()
        } catch (_: InvalidPathException) {
            false
        }
    }
}

context(_: OperationEventSink, terminal: Terminal)
internal suspend fun prepareEncryption(
    module: AmperModule,
    project: XcodeBuildSettingsResolution.FromModel.Resolver,
) {
    val plist = project.infoPlistPath?.let(module.source.moduleDir::resolve)
    val existing = if (plist != null) readEncryption(plist) else when (
        project.getSetting("INFOPLIST_KEY_ITSAppUsesNonExemptEncryption")?.uppercase()
    ) {
        "YES", "TRUE", "1" -> true
        "NO", "FALSE", "0" -> false
        else -> null
    }
    if (existing != null) return
    if (!terminal.terminalInfo.interactive) {
        // The declaration isn't required for the upload, so we don't stop the non-interactive flow because of it
        terminal.warning(
            "The app doesn't declare whether it uses non-exempt encryption (`ITSAppUsesNonExemptEncryption`). " +
                    "App Store Connect will show the build as 'Missing Compliance' in TestFlight until you answer " +
                    "the export compliance question there. To skip this step for future builds, set " +
                    "`ITSAppUsesNonExemptEncryption` in `Info.plist` (or `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption` " +
                    "for generated plists)."
        )
        return
    }
    val usesNonExemptEncryption = setupRequiresInput(
        title = "Export compliance: encryption",
        instructions = "**Does the app use non-exempt encryption?** " +
                "[Review Apple's guidance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance/). " +
                "Will be saved as `ITSAppUsesNonExemptEncryption` setting for the `Info.plist`.\n\n" +
                "*System-provided encryption, such as HTTPS, is often exempt. Custom or third-party cryptography may require documentation.*",
        fallback = "Set `ITSAppUsesNonExemptEncryption` in `Info.plist`, or " +
                "`INFOPLIST_KEY_ITSAppUsesNonExemptEncryption` for generated plists, after reviewing Apple's guidance.",
        block = {
            yesNoQuit("Does this app use non-exempt encryption?", default = false)
        },
    )
    if (plist != null) {
        writePlistValue(plist, "ITSAppUsesNonExemptEncryption", usesNonExemptEncryption)
    } else {
        project.setSetting("INFOPLIST_KEY_ITSAppUsesNonExemptEncryption", if (usesNonExemptEncryption) "YES" else "NO")
    }
}

private fun readEncryption(plist: Path): Boolean? =
    when (val value = readAppInfoPlist(plist)["ITSAppUsesNonExemptEncryption"]) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }
        else -> null
    }

internal fun alignPlistBundleId(
    module: AmperModule,
    project: XcodeBuildSettingsResolution.FromModel.Resolver,
    bundleId: String,
) {
    val plist = project.infoPlistPath?.let(module.source.moduleDir::resolve)
        ?: return
    val plistBundleId = (readAppInfoPlist(plist)["CFBundleIdentifier"] as? String)?.takeIf(String::isNotBlank)
    if (plistBundleId != null && '$' !in plistBundleId && plistBundleId != bundleId) {
        writePlistValue(plist, "CFBundleIdentifier", "$(PRODUCT_BUNDLE_IDENTIFIER)")
    }
}

private fun readAppInfoPlist(plist: Path): Plist {
    if (!plist.isRegularFile()) userReadableError("The app's Info.plist does not exist at '$plist'. Check INFOPLIST_FILE in Xcode.")
    return readPlist(plist)
}

private fun writePlistValue(plist: Path, key: String, value: Any) {
    val contents = readAppInfoPlist(plist)
    contents[key] = value
    writePlist(contents, plist)
}

private enum class AuthField(val label: String, val environmentVariable: String, val propertyName: String) {
    KeyPath("Private key path (.p8)", KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV, "keyPath"),
    KeyId("Key ID (10 uppercase letters/digits)", KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV, "keyId"),
    IssuerId("Issuer ID (UUID)", KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV, "issuerId"),
}

private val AuthFields = AuthField.entries
