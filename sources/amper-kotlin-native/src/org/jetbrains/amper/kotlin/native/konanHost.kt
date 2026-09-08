/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.kotlin.native

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo

/**
 * Returns the [KonanPlatform] that identifies a host with the given [system] in the Kotlin/Native distribution, or
 * null if Kotlin/Native doesn't support such a host.
 *
 * Kotlin/Native uses the same names for hosts and for targets, but only supports a handful of them as hosts. This
 * is the equivalent of `HostManager.hostName`.
 */
fun konanHostPlatform(system: SystemInfo = SystemInfo.CurrentHost): KonanPlatform? = when (system.family) {
    OsFamily.MacOs -> when (system.arch) {
        Arch.X64 -> Platform.MACOS_X64
        Arch.Arm64 -> Platform.MACOS_ARM64
    }
    OsFamily.Linux -> when (system.arch) {
        Arch.X64 -> Platform.LINUX_X64
        Arch.Arm64 -> Platform.LINUX_ARM64
    }
    OsFamily.Windows -> when (system.arch) {
        Arch.X64 -> Platform.MINGW_X64
        Arch.Arm64 -> null // Kotlin/Native has no ARM64 Windows host
    }
    OsFamily.FreeBSD,
    OsFamily.Solaris,
        -> null
}?.toKonanPlatform()
