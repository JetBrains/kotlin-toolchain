/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.consent

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JetBrainsConsentTest {

    @TempDir
    lateinit var sharedAppDataDir: Path

    private val consent: JetBrainsConsent get() = JetBrainsConsent(sharedAppDataDir)

    /**
     * The location that other JetBrains products read and write. It is part of the contract, so it is asserted
     * explicitly instead of being derived from the implementation.
     */
    private val consentFile: Path get() = sharedAppDataDir / "JetBrains" / "consentOptions" / "accepted"

    private fun writeConsentFileContents(contents: String) {
        consentFile.createParentDirectories().writeText(contents)
    }

    @Test
    fun readFlagsReturnsNullWhenTheFileDoesNotExist() {
        assertNull(consent.readFlags())
    }

    @Test
    fun readFlagsReturnsNoFlagsForAnEmptyFile() {
        writeConsentFileContents("")
        assertContentEquals([], consent.readFlags())
    }

    @Test
    fun readFlagsParsesASingleFlag() {
        writeConsentFileContents("rsch.send.usage.stat:1.1:1:1792422123479")
        assertContentEquals(
            [ConsentFlag(id = "rsch.send.usage.stat", version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479)],
            consent.readFlags(),
        )
    }

    @Test
    fun readFlagsParsesAllSemicolonSeparatedFlags() {
        writeConsentFileContents("rsch.send.usage.stat:1.1:1:1792422123479;eap.ii:2.0:0:1792422123480")
        assertContentEquals(
            [
                ConsentFlag(id = "rsch.send.usage.stat", version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479),
                ConsentFlag(id = "eap.ii", version = "2.0", state = ConsentState.Rejected, timestamp = 1792422123480),
            ],
            consent.readFlags(),
        )
    }

    @Test
    fun readFlagsSkipsMalformedFlagsButKeepsValidOnes() {
        val malformedFlags = listOf(
            "too:few:parts",
            "bad.timestamp:1.1:1:not-a-number",
            "unknown.state:1.1:2:1792422123479",
            "",
        )
        val validFlag = ConsentFlag(id = "rsch.send.usage.stat", version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479)
        writeConsentFileContents((malformedFlags + validFlag.serialize()).joinToString(";"))

        assertContentEquals([validFlag], consent.readFlags())
    }

    @Test
    fun writeFlagsCreatesTheSharedFileWithItsParentDirectories() {
        consent.writeFlags([ConsentFlag(id = "rsch.send.usage.stat", version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479)])

        assertTrue(consentFile.exists(), "the consent file should have been created at $consentFile")
        assertEquals("rsch.send.usage.stat:1.1:1:1792422123479", consentFile.readText())
    }

    @Test
    fun writeFlagsSeparatesFlagsWithSemicolons() {
        consent.writeFlags([
            ConsentFlag(id = "rsch.send.usage.stat", version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479),
            ConsentFlag(id = "eap.ii", version = "2.0", state = ConsentState.Rejected, timestamp = 1792422123480),
        ])

        assertEquals("rsch.send.usage.stat:1.1:1:1792422123479;eap.ii:2.0:0:1792422123480", consentFile.readText())
    }

    @Test
    fun writeFlagsReplacesTheWholeFileContents() {
        writeConsentFileContents("eap.ii:2.0:0:1792422123480")
        consent.writeFlags([ConsentFlag(id = "rsch.send.usage.stat", version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479)])

        assertEquals("rsch.send.usage.stat:1.1:1:1792422123479", consentFile.readText())
    }

    @Test
    fun writeFlagsIgnoresIoErrors() {
        // makes the creation of the consent file's parent directories fail
        (sharedAppDataDir / "JetBrains").createFile()

        consent.writeFlags([ConsentFlag(id = "rsch.send.usage.stat", version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479)])

        // if the write had somehow succeeded, this test wouldn't be covering the error path at all
        assertFalse(consentFile.exists(), "the consent file shouldn't be writable in this test")
    }

    @Test
    fun readFlagReturnsNullWhenTheFileDoesNotExist() {
        assertNull(consent.readFlag(ConsentFlag.FUS_ID))
    }

    @Test
    fun readFlagReturnsNullWhenNoFlagHasTheGivenId() {
        writeConsentFileContents("eap.ii:2.0:0:1792422123480")
        assertNull(consent.readFlag(ConsentFlag.FUS_ID))
    }

    @Test
    fun readFlagReturnsTheFlagWithTheGivenId() {
        writeConsentFileContents("eap.ii:2.0:0:1792422123480;rsch.send.usage.stat:1.1:1:1792422123479")
        assertEquals(
            ConsentFlag(id = ConsentFlag.FUS_ID, version = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479),
            consent.readFlag(ConsentFlag.FUS_ID),
        )
    }

    @Test
    fun writeFlagCreatesTheFileWhenItDoesNotExist() {
        consent.writeFlag(ConsentFlag.FUS_ID, consentBannerVersion = "1.1", state = ConsentState.Accepted, timestamp = 1792422123479)

        assertEquals("rsch.send.usage.stat:1.1:1:1792422123479", consentFile.readText())
    }

    @Test
    fun writeFlagStampsTheFlagWithTheCurrentTimeByDefault() {
        val before = System.currentTimeMillis()
        consent.writeFlag(ConsentFlag.FUS_ID, consentBannerVersion = "1.1", state = ConsentState.Accepted)
        val after = System.currentTimeMillis()

        val timestamp = assertNotNull(consent.readFlag(ConsentFlag.FUS_ID)?.timestamp)
        assertTrue(timestamp in before..after, "expected a timestamp in $before..$after but got $timestamp")
    }

    @Test
    fun writeFlagOverwritesTheFlagWithTheSameIdAndKeepsTheOthers() {
        writeConsentFileContents("eap.ii:2.0:0:1792422123480;rsch.send.usage.stat:1.1:1:1792422123479")

        consent.writeFlag(ConsentFlag.FUS_ID, consentBannerVersion = "1.2", state = ConsentState.Rejected, timestamp = 1792422999999)

        assertEquals("eap.ii:2.0:0:1792422123480;rsch.send.usage.stat:1.2:0:1792422999999", consentFile.readText())
    }

    @Test
    fun writeFlagDropsMalformedFlagsFromTheExistingFile() {
        writeConsentFileContents("garbage;rsch.send.usage.stat:1.1:1:1792422123479")

        consent.writeFlag(ConsentFlag.EAP_ID, consentBannerVersion = "2.0", state = ConsentState.Accepted, timestamp = 1792422999999)

        assertEquals("rsch.send.usage.stat:1.1:1:1792422123479;eap.ii:2.0:1:1792422999999", consentFile.readText())
    }
}
