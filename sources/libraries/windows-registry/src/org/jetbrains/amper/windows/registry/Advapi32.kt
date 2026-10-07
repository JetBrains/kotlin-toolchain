/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.windows.registry

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import kotlin.io.path.Path

/**
 * Represents the advapi32.dll library, which contains basic functions to access the Windows registry.
 *
 * Higher-level functions are provided in [WindowsRegistry], building on top of this.
 */
internal object Advapi32 {

    /**
     * See the function documentation to see what this return value means.
     */
    const val ERROR_SUCCESS = 0
    /**
     * See the function documentation to see what this return value means.
     */
    const val ERROR_FILE_NOT_FOUND = 2
    /**
     * See the function documentation to see what this return value means.
     */
    const val ERROR_MORE_DATA = 234

    const val RRF_RT_REG_SZ = 0x2
    const val RRF_RT_REG_EXPAND_SZ = 0x4
    const val RRF_RT_REG_DWORD = 0x10
    /**
     * Do not automatically expand environment strings if the value is of type `REG_EXPAND_SZ`.
     */
    const val RRF_NOEXPAND = 0x10000000

    /**
     * The [java.lang.foreign.SymbolLookup] for `advapi32.dll`, which contains the registry API.
     */
    private val lookup: SymbolLookup = run {
        val systemRoot = Path(System.getenv("SystemRoot") ?: "C:\\Windows")
        val dllPath = systemRoot.resolve("System32").resolve("advapi32.dll")
        SymbolLookup.libraryLookup(dllPath, Arena.global())
    }

    /**
     * `LSTATUS RegGetValueW(HKEY, LPCWSTR subKey, LPCWSTR value, DWORD flags, LPDWORD type, PVOID data, LPDWORD size)`
     *
     * See https://learn.microsoft.com/en-us/windows/win32/api/winreg/nf-winreg-reggetvaluew
     */
    private val RegGetValueWHandle: MethodHandle = Linker.nativeLinker().downcallHandle(
        lookup.findOrThrow("RegGetValueW"),
        // See https://learn.microsoft.com/en-us/windows/win32/api/winreg/nf-winreg-reggetvaluew#parameters
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,  // return: LSTATUS
            ValueLayout.JAVA_LONG, // [in] hkey: HKEY, represented as long to simplify (64-bit Windows only)
            ValueLayout.ADDRESS,   // [in, optional] lpSubKey: LPCWSTR
            ValueLayout.ADDRESS,   // [in, optional] lpValue: LPCWSTR
            ValueLayout.JAVA_INT,  // [in, optional] dwFlags: DWORD
            ValueLayout.ADDRESS,   // [out, optional] pdwType: LPDWORD
            ValueLayout.ADDRESS,   // [out, optional] pvData: PVOID
            ValueLayout.ADDRESS,   // [in, out, optional] pcbData: LPDWORD
        )
    )

    /**
     * Retrieves the type and data for the specified registry value.
     *
     * See https://learn.microsoft.com/en-us/windows/win32/api/winreg/nf-winreg-reggetvaluew
     *
     * @param hkey A handle to an open registry key. The key must have been opened with the KEY_QUERY_VALUE access
     *   right. For more information, see [Registry Key Security and Access Rights](https://learn.microsoft.com/en-us/windows/win32/sysinfo/registry-key-security-and-access-rights).
     *   It can also be one of the [predefined keys](https://learn.microsoft.com/en-us/windows/win32/sysinfo/predefined-keys).
     * @param lpSubKey The path of a registry key relative to the key specified by the [hkey] parameter. The registry
     *   value will be retrieved from this subkey. The path is not case-sensitive. If this parameter is `NULL` or an
     *   empty string, `""`, the value will be read from the key specified by [hkey] itself.
     * @param lpValue The name of the registry value to read. If this parameter is `NULL` or an empty string, `""`, the
     *   function retrieves the type and data for the key's unnamed or default value, if any. Keys do not
     *   automatically have an unnamed or default value, and unnamed values can be of any type. For more information,
     *   see [Registry Element Size Limits](https://learn.microsoft.com/en-us/windows/win32/sysinfo/registry-element-size-limits).
     * @param dwFlags The flags that restrict the data type of value to be queried. If the data type of the value does
     *   not meet these criteria, the function fails. This parameter can be one or more of the `RRF_RT_*` values (such
     *   as [RRF_RT_REG_SZ], [RRF_RT_REG_EXPAND_SZ], or [RRF_RT_REG_DWORD]), and can also include modifiers such as
     *   [RRF_NOEXPAND].
     * @param pdwType A pointer to a variable that receives a code indicating the type of data stored in the specified
     *   value. For a list of the possible type codes, see [Registry Value Types](https://learn.microsoft.com/en-us/windows/desktop/SysInfo/registry-value-types).
     *   This parameter can be `NULL` if the type is not required.
     * @param pvData A pointer to a buffer that receives the value's data. This parameter can be `NULL` if the data is
     *   not required. If the data is a string, the function checks for a terminating null character. If one is not
     *   found, the string is stored with a null terminator if the buffer is large enough to accommodate the extra
     *   character. Otherwise, the function fails and returns [ERROR_MORE_DATA].
     * @param pcbData A pointer to a variable that specifies the size of the buffer pointed to by the [pvData]
     *   parameter, in bytes. When the function returns, this variable contains the size of the data copied to
     *   [pvData]. It can be `NULL` only if [pvData] is `NULL`.
     *
     *   If the data has the `REG_SZ`, `REG_MULTI_SZ` or `REG_EXPAND_SZ` type, this size _includes_ any terminating null
     *   character or characters.
     *
     *   If the buffer specified by [pvData] is not large enough to hold the data, the function returns
     *   [ERROR_MORE_DATA] and stores the required buffer size in the variable pointed to by [pcbData].
     *
     *   If [pvData] is `NULL`, and [pcbData] is non-`NULL`, the function returns [ERROR_SUCCESS] and stores the size
     *   of the data, in bytes, in the variable pointed to by [pcbData]. This enables an application to determine the
     *   best way to allocate a buffer for the value's data.
     *
     * @return See https://learn.microsoft.com/en-us/windows/win32/api/winreg/nf-winreg-reggetvaluew#return-value
     *   * [ERROR_SUCCESS] on success
     *   * [ERROR_FILE_NOT_FOUND] if [lpValue] doesn't exist
     *   * [ERROR_MORE_DATA] if the [pvData] buffer is too small to hold the value
     */
    @Suppress("FunctionName")
    fun RegGetValueW(
        hkey: Long,
        lpSubKey: MemorySegment,
        lpValue: MemorySegment,
        dwFlags: Int,
        pdwType: MemorySegment,
        pvData: MemorySegment,
        pcbData: MemorySegment,
    ): Int = RegGetValueWHandle.invokeExact(
        hkey,
        lpSubKey,
        lpValue,
        dwFlags,
        pdwType,
        pvData,
        pcbData
    ) as Int
}
