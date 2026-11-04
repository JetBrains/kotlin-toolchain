/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.userid

import org.jetbrains.amper.apple.ffm.CoreFoundation
import org.jetbrains.amper.apple.ffm.CoreFoundation.KCF_STRING_ENCODING_UTF8
import org.jetbrains.amper.apple.ffm.IOKit
import org.jetbrains.amper.apple.ffm.withCFString
import org.jetbrains.amper.apple.ffm.withService
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * The macOS machine id, read from the I/O Registry with IOKit and CoreFoundation downcalls.
 * The first call loads both frameworks.
 */
internal object MacOSMachineId {

    private const val UUID_BUFFER_SIZE: Long = 256

    /**
     * See [IOPlatformExpertDevice](https://developer.apple.com/documentation/kernel/ioplatformexpertdevice).
     *
     * @return the `IOPlatformUUID` property of `IOPlatformExpertDevice`, or `null` when the registry has none
     */
    fun findUuid(): String? {
        Arena.ofConfined().use { arena ->
            IOKit.withService("IOPlatformExpertDevice", arena = arena) { ioPlatformExpertDeviceService ->
                if (ioPlatformExpertDeviceService == 0) {
                    return null
                }
                CoreFoundation.withCFString("IOPlatformUUID", arena = arena) { ioPlatformUuidKey ->
                    val uuid = IOKit.IORegistryEntryCreateCFProperty(ioPlatformExpertDeviceService, ioPlatformUuidKey)
                    if (uuid == MemorySegment.NULL) {
                        return null
                    }
                    try {
                        val buffer = arena.allocate(UUID_BUFFER_SIZE)
                        // asking for UTF-8 because buffer.getString() expects UTF-8
                        val copied = CoreFoundation.CFStringGetCString(uuid, buffer, encoding = KCF_STRING_ENCODING_UTF8)
                        return if (copied) buffer.getString(0) else null
                    } finally {
                        CoreFoundation.CFRelease(uuid)
                    }
                }
            }
        }
    }
}
