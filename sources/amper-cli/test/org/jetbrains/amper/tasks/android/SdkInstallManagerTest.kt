/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdkInstallManagerTest {

    // A realistic slice of the platform packages currently published in the Android SDK repository.
    // Note that "platforms;android-37" is intentionally absent: API 37 is only published as 37.0.
    private val availablePlatforms = listOf(
        "platforms;android-35",
        "platforms;android-35-ext14",
        "platforms;android-36",
        "platforms;android-36-ext18",
        "platforms;android-36-ext19",
        "platforms;android-36.1",
        "platforms;android-37.0",
        "platforms;android-CinnamonBun",
        "platforms;android-CinnamonBun-ext23",
    )

    @Test
    fun `exact match is preferred`() {
        // android-36 exists as a plain package, so it must win over android-36.1
        assertEquals(
            "platforms;android-36",
            selectBestMatchingPackagePath("platforms;android-36", availablePlatforms),
        )
    }

    @Test
    fun `minor API level variant is resolved when plain is absent`() {
        // android-37 has no plain package, only android-37.0
        assertEquals(
            "platforms;android-37.0",
            selectBestMatchingPackagePath("platforms;android-37", availablePlatforms),
        )
    }

    @Test
    fun `highest minor API level is chosen`() {
        val available = listOf(
            "platforms;android-37.0",
            "platforms;android-37.1",
            "platforms;android-37.2",
        )
        assertEquals(
            "platforms;android-37.2",
            selectBestMatchingPackagePath("platforms;android-37", available),
        )
    }

    @Test
    fun `latest minor api level is chosen over an exact match`() {
        val available = listOf(
            "platforms;android-37",
            "platforms;android-37.0",
            "platforms;android-37.1",
        )
        assertEquals(
            "platforms;android-37.1",
            selectLatestMinorApiLevelPackagePath("platforms;android-37", available),
        )
    }

    @Test
    fun `latest minor api level is chosen before an extension suffix`() {
        val available = listOf(
            "platforms;android-37-ext2",
            "platforms;android-37.0-ext2",
            "platforms;android-37.1-ext2",
        )
        assertEquals(
            "platforms;android-37.1-ext2",
            selectLatestMinorApiLevelPackagePath("platforms;android-37-ext2", available),
        )
    }

    @Test
    fun `extension levels and codenames are never matched if not requested`() {
        val available = listOf(
            "platforms;android-37-ext19",
            "platforms;android-CinnamonBun",
            "platforms;android-CinnamonBun-ext23",
        )
        assertNull(selectBestMatchingPackagePath("platforms;android-37", available))
    }

    @Test
    fun `match extension level when requested`() {
        assertEquals(
            "platforms;android-36-ext19",
            selectBestMatchingPackagePath("platforms;android-36-ext19", availablePlatforms),
        )
    }

    @Test
    fun `prefix of an api level is not matched`() {
        // "android-3" must not match "android-37"/"android-37.0" (and "android-370" has no minor API level dot)
        val available = listOf("platforms;android-37", "platforms;android-37.0", "platforms;android-370")
        assertNull(selectBestMatchingPackagePath("platforms;android-3", available))
    }

    @Test
    fun `build tools exact match is resolved`() {
        val available = listOf("build-tools;36.0.0", "build-tools;36.1.0", "build-tools;37.0.0")
        assertEquals(
            "build-tools;37.0.0",
            selectBestMatchingPackagePath("build-tools;37.0.0", available),
        )
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(selectBestMatchingPackagePath("platforms;android-99", availablePlatforms))
    }

    @Test
    fun `platform package name includes the configured minor API level and extension`() {
        assertEquals(
            "platforms;android-37.1-ext2",
            androidPlatformPackageName(apiLevel = 37, minorApiLevel = 1, sdkExtension = 2),
        )
    }

    // See comment in the androidPlatformPackageName
    @Test
    fun `platform package name handles minor API level boundaries`() {
        data class Case(val apiLevel: Int, val minorApiLevel: Int?, val expected: String)

        val cases = [
            Case(35, null, "platforms;android-35"),
            Case(35, 0, "platforms;android-35"),
            Case(36, 0, "platforms;android-36"),
            Case(36, 1, "platforms;android-36.1"),
            Case(37, null, "platforms;android-37"),
            Case(37, 0, "platforms;android-37.0"),
        ]

        cases.forEach { case ->
            assertEquals(
                case.expected,
                androidPlatformPackageName(case.apiLevel, case.minorApiLevel, sdkExtension = null),
            )
        }
    }

    @Test
    fun `platform package name appends extension without minor API level`() {
        assertEquals(
            "platforms;android-35-ext14",
            androidPlatformPackageName(apiLevel = 35, minorApiLevel = 0, sdkExtension = 14),
        )
    }

    @Test
    fun `latest minor API level is checked only when it is unspecified for API 37 and later`() {
        assertTrue(shouldCheckForNewerAndroidPlatformMinorApiLevel(apiLevel = 37, minorApiLevel = null))
        assertTrue(shouldCheckForNewerAndroidPlatformMinorApiLevel(apiLevel = 36, minorApiLevel = null))
        assertTrue(shouldCheckForNewerAndroidPlatformMinorApiLevel(apiLevel = 42, minorApiLevel = null))

        assertFalse(shouldCheckForNewerAndroidPlatformMinorApiLevel(apiLevel = 37, minorApiLevel = 0))
        assertFalse(shouldCheckForNewerAndroidPlatformMinorApiLevel(apiLevel = 36, minorApiLevel = 0))
        assertFalse(shouldCheckForNewerAndroidPlatformMinorApiLevel(apiLevel = 35, minorApiLevel = null))
    }
}
