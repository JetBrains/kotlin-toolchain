/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.consent

import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.system.info.getSharedUserAppDataRoot
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = LoggerFactory.getLogger("FUS-consent")

/**
 * Provides access to the data-sharing consent file shared by all JetBrains products.
 */
internal class JetBrainsConsent(os: OsFamily = SystemInfo.CurrentHost.family) {

    /**
     * The data-sharing consent file shared by all JetBrains products.
     *
     * See `com.intellij.ide.gdpr.ConsentOptions.getConfirmedConsentsFile` in the monorepo.
     * https://github.com/JetBrains/intellij-community/blob/4af2a7334574fc190881928c0f0a82cb2ff59223/platform/platform-impl/src/com/intellij/ide/gdpr/ConsentOptions.java#L79-L81
     */
    private val dataSharingConsentFile: Path = os.getSharedUserAppDataRoot() / "JetBrains/consentOptions/accepted"

    /**
     * Reads the consent flags from the consent file shared by all JetBrains products,
     * or `null` if the file does not exist.
     */
    fun readFlags(): List<ConsentFlag>? {
        if (!dataSharingConsentFile.exists()) {
            return null
        }
        return dataSharingConsentFile.readText().split(";").mapNotNull { ConsentFlag.parseOrNull(it) }
    }

    /**
     * Writes the given consent [flags] to the consent file shared by all JetBrains products,
     * creating the file if it doesn't exist
     */
    fun writeFlags(flags: Iterable<ConsentFlag>) {
        try {
            val serializedFlags = flags.joinToString(";") { it.serialize() }
            dataSharingConsentFile.createParentDirectories().writeText(serializedFlags)
        } catch (e: IOException) {
            // Failing to write consent (e.g. in case of permission issues) isn't an error to users
            logger.debug("Could not write consent flags to file: {}", dataSharingConsentFile, e)
        }
    }
}

/**
 * Reads the consent flag with the given [id] from the consent file shared by all JetBrains products,
 * or `null` if the consent file does not exist.
 */
internal fun JetBrainsConsent.readFlag(id: String): ConsentFlag? = readFlags()?.find { id == it.id }

/**
 * Writes (or overwrites) the consent state for the flag with the given [id],
 * creating the consent file if it doesn't exist.
 *
 * The [consentBannerVersion] should match the version of the consent banner that was shown to the user.
 */
internal fun JetBrainsConsent.writeFlag(id: String, consentBannerVersion: String, state: ConsentState) {
    val oldFlags = readFlags() ?: return
    val flagsMap = oldFlags.associateByTo(mutableMapOf()) { it.id }
    flagsMap[id] = ConsentFlag(id, consentBannerVersion, state, System.currentTimeMillis())
    writeFlags(flagsMap.values)
}

internal data class ConsentFlag(
    val id: String,
    val version: String,
    val state: ConsentState,
    val timestamp: Long,
) {
    fun serialize() = "$id:$version:${state.valueInFile}:$timestamp"

    companion object {
        /**
         * The shared flag for sending usage statistics to JetBrains.
         */
        const val FUS_ID = "rsch.send.usage.stat"
        const val EAP_ID = "eap.ii"

        /**
         * Parses a consent flag of the form `id:version:accepted:timestamp`.
         *
         * Example: `rsch.send.usage.stat:1.1:1:1792422123479`
         */
        fun parseOrNull(string: String): ConsentFlag? {
            val parts = string.split(":")
            if (parts.size < 4) {
                logger.debug("Could not parse consent flag: {}", string)
                return null
            }
            val timestamp = parts[3].toLongOrNull()
            if (timestamp == null) {
                logger.debug("Could not parse consent flag's timestamp: {}", parts[3])
                return null
            }
            val state = ConsentState.entries.find { it.valueInFile == parts[2] }
            if (state == null) {
                logger.debug("Could not parse consent flag's state: {}", parts[2])
                return null
            }
            return ConsentFlag(
                id = parts[0],
                version = parts[1],
                state = state,
                timestamp = timestamp,
            )
        }
    }
}

enum class ConsentState(internal val valueInFile: String) {
    Accepted("1"),
    Rejected("0"),
}
