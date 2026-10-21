/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.userid

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

/**
 * The macOS machine id, read from the I/O Registry with IOKit and CoreFoundation downcalls.
 * The first call loads both frameworks.
 */
internal object MacOSMachineId {
    private const val KCF_STRING_ENCODING_UTF8 = 0x08000100
    private const val UUID_BUFFER_SIZE: Long = 256

    /**
     * See [IOPlatformExpertDevice](https://developer.apple.com/documentation/kernel/ioplatformexpertdevice).
     *
     * @return the `IOPlatformUUID` property of `IOPlatformExpertDevice`, or `null` when the registry has none
     */
    fun findUuid(): String? {
        Arena.ofConfined().use { arena ->
            // IOServiceGetMatchingService consumes one reference of the matching dictionary.
            val serviceName = arena.allocateFrom("IOPlatformExpertDevice")
            val serviceMatching = IOKit.IOServiceMatching.invokeExact(serviceName) as MemorySegment?
            val service = IOKit.IOServiceGetMatchingService.invokeExact(0, serviceMatching) as Int
            if (service == 0) {
                return null
            }
            try {
                val key = CoreFoundation.CFStringCreateWithCString.invokeExact(
                    MemorySegment.NULL, arena.allocateFrom("IOPlatformUUID"), KCF_STRING_ENCODING_UTF8
                ) as MemorySegment?
                try {
                    val uuid = IOKit.IORegistryEntryCreateCFProperty.invokeExact(
                        service,
                        key,
                        MemorySegment.NULL,
                        0
                    ) as MemorySegment
                    if (uuid == MemorySegment.NULL) {
                        return null
                    }
                    try {
                        val buffer = arena.allocate(UUID_BUFFER_SIZE)
                        val copied = CoreFoundation.CFStringGetCString.invokeExact(
                            uuid,
                            buffer,
                            UUID_BUFFER_SIZE,
                            KCF_STRING_ENCODING_UTF8
                        ) as Byte
                        return if (copied.toInt() != 0) buffer.getString(0) else null
                    } finally {
                        CoreFoundation.CFRelease.invokeExact(uuid)
                    }
                } finally {
                    CoreFoundation.CFRelease.invokeExact(key)
                }
            } finally {
                IOKit.IOObjectRelease.invokeExact(service)
            }
        }
    }
}

private val LINKER: Linker = Linker.nativeLinker()

private fun SymbolLookup.downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
    LINKER.downcallHandle(findOrThrow(name), descriptor)

/**
 * Downcalls into the IOKit framework.
 */
private object IOKit {

    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/IOKit.framework/IOKit",
        Arena.global(),
    )

    val IOServiceMatching: MethodHandle = lookup.downcall(
        "IOServiceMatching", FunctionDescriptor.of(ADDRESS, ADDRESS),
    )
    val IOServiceGetMatchingService: MethodHandle = lookup.downcall(
        "IOServiceGetMatchingService", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS),
    )
    val IORegistryEntryCreateCFProperty: MethodHandle = lookup.downcall(
        "IORegistryEntryCreateCFProperty", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
    )
    val IOObjectRelease: MethodHandle = lookup.downcall(
        "IOObjectRelease", FunctionDescriptor.of(JAVA_INT, JAVA_INT),
    )
}

/**
 * Downcalls into the CoreFoundation framework.
 */
private object CoreFoundation {

    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
        Arena.global(),
    )

    val CFStringCreateWithCString: MethodHandle = lookup.downcall(
        "CFStringCreateWithCString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
    )
    val CFStringGetCString: MethodHandle = lookup.downcall(
        "CFStringGetCString", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT),
    )
    val CFRelease: MethodHandle = lookup.downcall(
        "CFRelease", FunctionDescriptor.ofVoid(ADDRESS),
    )
}
