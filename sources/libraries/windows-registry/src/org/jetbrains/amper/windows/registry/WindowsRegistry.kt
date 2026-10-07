/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.windows.registry

import org.jetbrains.amper.windows.registry.Advapi32.ERROR_FILE_NOT_FOUND
import org.jetbrains.amper.windows.registry.Advapi32.ERROR_MORE_DATA
import org.jetbrains.amper.windows.registry.Advapi32.ERROR_SUCCESS
import org.jetbrains.amper.windows.registry.Advapi32.RRF_NOEXPAND
import org.jetbrains.amper.windows.registry.Advapi32.RRF_RT_REG_DWORD
import org.jetbrains.amper.windows.registry.Advapi32.RRF_RT_REG_EXPAND_SZ
import org.jetbrains.amper.windows.registry.Advapi32.RRF_RT_REG_SZ
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import java.nio.charset.StandardCharsets

/**
 * A registry call failed.
 *
 * [errorCode] is the `LSTATUS` it returned.
 */
class RegistryException(function: String, val errorCode: Int) :
    IOException("$function failed with Win32 error $errorCode")

/**
 * Read access to the Windows registry through `advapi32.dll` downcalls. The first call loads the DLL.
 *
 * A missing key or value is a `null` result, not an error. Every other `LSTATUS` throws [RegistryException] with
 * the code.
 */
object WindowsRegistry {

    /**
     * How many times a read is attempted while the value doesn't fit the buffer.
     *
     * A single retry suffices unless the value keeps growing between calls, which these extra attempts cover.
     */
    private const val MAX_READ_ATTEMPTS = 5

    /**
     * Gets a `REG_SZ` or `REG_EXPAND_SZ` value, or `null` if the key or the value does not exist.
     *
     * @param expandVariables Whether to expand environment variables in the value (only affects `REG_EXPAND_SZ` values).
     * @param expectedMaxLength An estimation of the length that the string value is not expected to exceed, in
     *   characters (and excluding the NUL terminator).
     *
     *   This is only a performance hint used to decide the initial size of the buffer used to read values:
     *   If the value fits, the data is read in a single registry call.
     *   If the value is longer, an extra call is made with a bigger buffer (wasting the original buffer and an
     *   extra call).
     *
     *   When `null`, the size of the value is queried from the registry first, which always costs an extra call.
     *
     *   If the value grows concurrently with the sequence of calls, the read is retried with a new, bigger buffer
     *   matching the new size.
     *
     * @return the string value, or `null` if the key or the value does not exist
     *
     * @throws RegistryException if the value is not a `REG_SZ` or `REG_EXPAND_SZ` (string), or if another error occurs
     */
    fun getString(
        hive: Hive,
        key: String,
        value: String,
        expandVariables: Boolean = true,
        expectedMaxLength: Int? = null,
    ): String? {
        require(expectedMaxLength == null || expectedMaxLength >= 0) {
            "expectedMaxLength cannot be negative, got $expectedMaxLength"
        }
        val typeFlags = RRF_RT_REG_SZ or RRF_RT_REG_EXPAND_SZ
        val flags = if (expandVariables) typeFlags else typeFlags or RRF_NOEXPAND

        // +1 for the NUL terminator, which RegGetValueW adds to the buffer if the stored value doesn't have one.
        val initialBufferSize = if (expectedMaxLength == null) null else (expectedMaxLength + 1) * Char.SIZE_BYTES
        return getValue(hive, key, value, flags, initialBufferSize)?.decodeUtf16LE()
    }

    /**
     * Decodes a `REG_SZ` buffer; the text ends at the first terminator, or at the end of the buffer without one.
     */
    private fun ByteArray.decodeUtf16LE(): String = String(this, StandardCharsets.UTF_16LE).substringBefore('\u0000')

    /**
     * Gets a `REG_DWORD` value, or `null` if the key or the value does not exist.
     *
     * @throws RegistryException if the value is not a `REG_DWORD`, or if another error occurs
     */
    fun getInt(hive: Hive, key: String, value: String): Int? {
        // A REG_DWORD is always 4 bytes, so a single call with an exact buffer is enough.
        val data = getValue(hive, key, value, RRF_RT_REG_DWORD, initialBufferSize = Int.SIZE_BYTES) ?: return null
        if (data.size != Int.SIZE_BYTES) {
            throw RegistryException("RegGetValueW($value) returned ${data.size} bytes for a DWORD", ERROR_MORE_DATA)
        }
        // A heap segment over a byte array has byte alignment, so the aligned JAVA_INT layout rejects it.
        return MemorySegment.ofArray(data).get(JAVA_INT_UNALIGNED, 0)
    }

    /**
     * Reads the raw bytes of the given [value] under [hive]\\[key] with `RegGetValueW`.
     *
     * If [initialBufferSize] is `null`, the size of the data is first queried from the registry before reading it, so
     * we know the size of the buffer to allocate.
     *
     * To save on the extra call, pass a non-null [initialBufferSize]. In that case, this function first tries to read
     * the data into a buffer of size [initialBufferSize], so values that fit are read in a single call.
     * For a bigger value, `RegGetValueW` reports the size it needs and the read is retried with a buffer of that size.
     *
     * Even when querying with right-sized buffer, the value might have grown concurrently. Thus, we might need to retry
     * multiple times. Up to [MAX_READ_ATTEMPTS] are made, after which a [RegistryException] is thrown if the value kept
     * growing.
     */
    @Throws(RegistryException::class)
    private fun getValue(hive: Hive, key: String, value: String, flags: Int, initialBufferSize: Int?): ByteArray? {
        Arena.ofConfined().use { arena ->
            val subKey = arena.allocateFrom(key, StandardCharsets.UTF_16LE)
            val valueName = arena.allocateFrom(value, StandardCharsets.UTF_16LE)
            val size = arena.allocate(JAVA_INT)

            // Calls RegGetValueW into the given buffer, leaving in `size` the size of the data, or the size it needs.
            // Pass MemorySegment.NULL and 0 to only query the size.
            fun regGetValue(data: MemorySegment, bufferSize: Int): Int {
                size.set(JAVA_INT, 0, bufferSize)
                return Advapi32.RegGetValueW(
                    hkey = hive.handle,
                    lpSubKey = subKey,
                    lpValue = valueName,
                    dwFlags = flags,
                    pdwType = MemorySegment.NULL, // we already know the type because we select it via flags
                    pvData = data,
                    pcbData = size,
                )
            }

            var bufferSize = initialBufferSize ?: run {
                val result = regGetValue(data = MemorySegment.NULL, bufferSize = 0)
                if (result == ERROR_FILE_NOT_FOUND) {
                    return null
                }
                if (result != ERROR_SUCCESS) {
                    throw RegistryException("RegGetValueW($key\\$value)", errorCode = result)
                }
                size.get(JAVA_INT, 0)
            }

            repeat(MAX_READ_ATTEMPTS) {
                val data = arena.allocate(bufferSize.toLong())
                when (val result = regGetValue(data, bufferSize)) {
                    ERROR_SUCCESS -> return data.asSlice(0, size.get(JAVA_INT, 0).toLong()).toArray(JAVA_BYTE)
                    ERROR_FILE_NOT_FOUND -> return null
                    ERROR_MORE_DATA -> bufferSize = size.get(JAVA_INT, 0)
                    else -> throw RegistryException("RegGetValueW($key\\$value)", errorCode = result)
                }
            }
            throw RegistryException(
                "RegGetValueW($key\\$value) failed $MAX_READ_ATTEMPTS times with ERROR_MORE_DATA, giving up. " +
                        "This means the value grew in size after each attempt, so each new buffer was too small, " +
                        "despite increasing the size to the last known size of the data.",
                errorCode = ERROR_MORE_DATA,
            )
        }
    }

    /**
     * A predefined root key.
     */
    enum class Hive(value: UInt) {
        CURRENT_USER(0x80000001u),
        LOCAL_MACHINE(0x80000002u);

        // `winreg.h` defines them as `(HKEY)(ULONG_PTR)((LONG)value)`, so the negative int value is sign-extended.
        // This is why we go through UInt -> Int -> Long transformations here.
        internal val handle: Long = value.toInt().toLong()
    }
}
