/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.windows.registry

import org.jetbrains.amper.windows.registry.WindowsRegistry.Hive
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests reading values that Windows itself maintains, so that nothing has to be written to the live registry.
 *
 * Every key used here is created by Windows setup or when a user profile is created, so they should always be present.
 * The asserted values are either cross-checked against an independent source (an environment variable, a JVM property)
 * or only constrained loosely, because their exact contents depend on the machine / locale.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsRegistryTest {

    @Test
    fun `getString reads a REG_SZ value`() {
        val actual = WindowsRegistry.getString(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "SystemRoot")
        val expected = System.getenv("SystemRoot")
        assertTrue(expected.equals(actual, ignoreCase = true), "expected '$expected', got '$actual'")
    }

    @Test
    fun `getString reads a REG_SZ value spanning many bytes`() {
        val actual = WindowsRegistry.getString(Hive.LOCAL_MACHINE, "$SESSION_MANAGER\\Environment", "Path")
        val systemPath = assertNotNull(actual)
        assertTrue(systemPath.length > 50, "expected a long system PATH, got '$systemPath'")
    }

    @Test
    fun `getString reads a value that fits exactly in the expected length in a single call`() {
        val actual = WindowsRegistry.getString(
            hive = Hive.LOCAL_MACHINE,
            WINDOWS_CURRENT_VERSION,
            "ProgramFilesPath",
            expectedMaxLength = PROGRAM_FILES_PATH_VALUE.length,
            expandVariables = false,
        )
        assertEquals(PROGRAM_FILES_PATH_VALUE, actual)
    }

    @Test
    fun `getString reads the whole value when it is one character longer than expected`() {
        val actual = WindowsRegistry.getString(
            Hive.LOCAL_MACHINE,
            WINDOWS_CURRENT_VERSION,
            "ProgramFilesPath",
            expectedMaxLength = PROGRAM_FILES_PATH_VALUE.length - 1,
            expandVariables = false,
        )
        assertEquals(PROGRAM_FILES_PATH_VALUE, actual)
    }

    @Test
    fun `getString reads the whole value when it is far longer than expected`() {
        val key = "$SESSION_MANAGER\\Environment"
        val withoutExpectedLength = WindowsRegistry.getString(Hive.LOCAL_MACHINE, key, "Path")
        val withShortExpectedLength = WindowsRegistry.getString(Hive.LOCAL_MACHINE, key, "Path", expectedMaxLength = 0)
        assertEquals(withoutExpectedLength, withShortExpectedLength)
    }

    @Test
    fun `getString reads the same value with a queried size and with an exact expected length`() {
        val key = "$SESSION_MANAGER\\Environment"
        val withoutExpectedLength = assertNotNull(WindowsRegistry.getString(Hive.LOCAL_MACHINE, key, "Path"))
        val withExpectedLength =
            WindowsRegistry.getString(Hive.LOCAL_MACHINE, key, "Path", expectedMaxLength = withoutExpectedLength.length)
        assertEquals(withoutExpectedLength, withExpectedLength)
    }

    @Test
    fun `getString rejects a negative expected length`() {
        assertFailsWith<IllegalArgumentException> {
            WindowsRegistry.getString(
                Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "SystemRoot", expectedMaxLength = -1
            )
        }
    }

    @Test
    fun `getString reads an empty value`() {
        assertEquals("", WindowsRegistry.getString(Hive.LOCAL_MACHINE, "$SESSION_MANAGER\\SubSystems", "Debug"))
    }

    @Test
    fun `getString reads a REG_EXPAND_SZ value without expanding it`() {
        val actual = WindowsRegistry.getString(
            hive = Hive.LOCAL_MACHINE,
            key = WINDOWS_CURRENT_VERSION,
            value = "ProgramFilesPath",
            expandVariables = false,
        )
        assertEquals(PROGRAM_FILES_PATH_VALUE, actual)
    }

    @Test
    fun `getString reads the default value of a key when the value name is empty`() {
        // The text itself is localized, so only its presence is asserted.
        val directoryClassName = assertNotNull(
            WindowsRegistry.getString(Hive.LOCAL_MACHINE, "SOFTWARE\\Classes\\Directory", "")
        )
        assertTrue(directoryClassName.isNotBlank(), "expected a display name for the Directory class")
    }

    @Test
    fun `getString reads a value under a key path containing spaces`() {
        // The text itself is localized, so only its presence is asserted.
        val productName = assertNotNull(
            WindowsRegistry.getString(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "ProductName")
        )
        assertTrue(productName.isNotBlank(), "expected a product name")
    }

    @Test
    fun `getString returns null for a missing value`() {
        assertNull(WindowsRegistry.getString(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "NoSuchValue"))
    }

    @Test
    fun `getString returns null for a missing key`() {
        assertNull(WindowsRegistry.getString(Hive.LOCAL_MACHINE, "NotARealExistingKey", "SystemRoot"))
    }

    @Test
    fun `getString fails for a REG_DWORD value`() {
        assertFailsWithUnsupportedType {
            WindowsRegistry.getString(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "CurrentMajorVersionNumber")
        }
    }

    @Test
    fun `getString fails for a REG_QWORD value`() {
        assertFailsWithUnsupportedType {
            WindowsRegistry.getString(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "InstallTime")
        }
    }

    @Test
    fun `getString fails for a REG_BINARY value`() {
        assertFailsWithUnsupportedType {
            WindowsRegistry.getString(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "DigitalProductId")
        }
    }

    @Test
    fun `getString fails for a REG_MULTI_SZ value`() {
        assertFailsWithUnsupportedType { WindowsRegistry.getString(Hive.LOCAL_MACHINE, SESSION_MANAGER, "BootExecute") }
    }

    @Test
    fun `getInt reads a REG_DWORD value`() {
        val winMajorVersion =
            WindowsRegistry.getInt(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "CurrentMajorVersionNumber")
        val majorVersion =
            assertNotNull(winMajorVersion, "CurrentMajorVersionNumber should exist on Windows 10 and later")
        assertEquals(System.getProperty("os.version").substringBefore('.').toInt(), majorVersion)
    }

    @Test
    fun `getInt returns null for a missing value`() {
        assertNull(WindowsRegistry.getInt(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "NoSuchValue"))
    }

    @Test
    fun `getInt returns null for a missing key`() {
        assertNull(WindowsRegistry.getInt(Hive.LOCAL_MACHINE, "NotARealExistingKey", "CurrentMajorVersionNumber"))
    }

    @Test
    fun `getInt fails for a REG_SZ value`() {
        assertFailsWithUnsupportedType {
            WindowsRegistry.getInt(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "SystemRoot")
        }
    }

    @Test
    fun `getInt fails for a REG_QWORD value`() {
        assertFailsWithUnsupportedType {
            WindowsRegistry.getInt(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "InstallTime")
        }
    }

    @Test
    fun `getInt fails for a REG_BINARY value`() {
        assertFailsWithUnsupportedType {
            WindowsRegistry.getInt(Hive.LOCAL_MACHINE, WINDOWS_NT_CURRENT_VERSION, "DigitalProductId")
        }
    }

    @Test
    fun `can read from the CURRENT_USER hive`() {
        val actual = WindowsRegistry.getString(Hive.CURRENT_USER, "$WINDOWS_CURRENT_VERSION\\Explorer\\User Shell Folders", "AppData")
        assertNotNull(actual, "AppData should not be null")
        val expected = System.getenv("APPDATA")
        assertEquals(Path(expected).toRealPath(), Path(actual).toRealPath())
    }
}

private const val WINDOWS_NT_CURRENT_VERSION = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"
private const val WINDOWS_CURRENT_VERSION = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion"
private const val SESSION_MANAGER = "SYSTEM\\CurrentControlSet\\Control\\Session Manager"

/**
 * The unexpanded contents of the `ProgramFilesPath` value, used to test the buffer size boundaries exactly.
 */
private const val PROGRAM_FILES_PATH_VALUE = "%ProgramFiles%"

/**
 * The Win32 error returned when the data is not of one of the types requested by the `RRF_RT_*` flags.
 */
private const val ERROR_UNSUPPORTED_TYPE = 1630

private inline fun assertFailsWithUnsupportedType(read: () -> Any?) {
    val exception = assertFailsWith<RegistryException> { read() }
    assertEquals(ERROR_UNSUPPORTED_TYPE, exception.errorCode)
}
