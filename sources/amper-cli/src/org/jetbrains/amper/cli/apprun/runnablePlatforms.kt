/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.util

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.SystemInfo

/**
 * The leaf [org.jetbrains.amper.frontend.Platform]s that can be "run" from the current host.
 *
 * That is, code compiled to the returned [org.jetbrains.amper.frontend.Platform]s can be run in any of the following ways:
 * * on this host directly
 * * in an emulator that runs on this host
 * * on a suitable physical device connected to this host
 *
 * Note: the actual presence of connected physical devices is not checked here.
 * Platforms that correspond to connected devices are returned as part of the set based on whether it would be
 * _possible_ to run them, should a suitable device be connected.
 */
internal val currentHostRunnablePlatforms: Set<Platform> by lazy { SystemInfo.CurrentHost.runnablePlatforms() }

/**
 * Returns the leaf [Platform]s that can be "run" from a host with this [SystemInfo].
 *
 * That is, code compiled to the returned [Platform]s can be run in any of the following ways:
 * * on the host directly
 * * in an emulator that runs on the host
 * * on a suitable physical device connected to the host
 *
 * Note: the actual presence of connected physical devices is not checked here.
 * Platforms that correspond to connected devices are returned as part of the set based on whether it would be
 * _possible_ to run them, should a suitable device be connected.
 */
private fun SystemInfo.runnablePlatforms(): Set<Platform> =
    Platform.entries.filterTo(mutableSetOf()) { platform -> platform.isRunnableFrom(this) }

/**
 * Whether code compiled to this leaf [Platform] can be run from the given [system].
 *
 * That is, whether that code can be run in any of the following ways:
 * * on the host directly
 * * in an emulator that runs on the host
 * * on a suitable physical device connected to the host
 *
 * Note: the actual presence of connected physical devices is not checked here.
 * If this [Platform] corresponds to a connected physical device, this function returns `true` based on whether it
 * would be _possible_ to run it, should a suitable device be connected.
 */
private fun Platform.isRunnableFrom(system: SystemInfo): Boolean = when (this) {
    Platform.COMMON,
    Platform.WEB,
    Platform.NATIVE,
    Platform.LINUX,
    Platform.APPLE,
    Platform.TVOS,
    Platform.IOS,
    Platform.MACOS,
    Platform.WATCHOS,
    Platform.MINGW,
    Platform.ANDROID_NATIVE,
        -> false // non-leaf platforms are not runnable anywhere
    Platform.JVM,
    Platform.JS,
    Platform.WASM_JS,
    Platform.WASM_WASI,
    Platform.ANDROID,
        -> true // system-independent, always runnable
    Platform.ANDROID_NATIVE_ARM32,
    Platform.ANDROID_NATIVE_ARM64,
    Platform.ANDROID_NATIVE_X64,
    Platform.ANDROID_NATIVE_X86,
        -> false // these platforms are meant for libraries and are not really supposed to be run directly
    Platform.LINUX_X64,
        -> system.family.isLinux && system.arch == Arch.X64
    Platform.LINUX_ARM64,
        -> system.family.isLinux && system.arch == Arch.Arm64
    Platform.MINGW_X64,
        -> system.family.isWindows && system.arch == Arch.X64
    Platform.MACOS_X64,
        -> system.family.isMac // macosX64 can run on any macOS (even ARM64 with Rosetta)
    Platform.MACOS_ARM64,
        -> system.family.isMac && system.arch == Arch.Arm64
    Platform.IOS_SIMULATOR_ARM64,
    Platform.TVOS_SIMULATOR_ARM64,
    Platform.WATCHOS_SIMULATOR_ARM64,
        -> system.family.isMac && system.arch == Arch.Arm64 // ARM64 simulators can only run on ARM64 macOS
    Platform.IOS_X64,
    Platform.TVOS_X64,
        -> system.family.isMac && system.arch == Arch.X64 // we only run X64 simulators on macOS X64 (avoid Rosetta)
    Platform.IOS_ARM64,
    Platform.TVOS_ARM64,
    Platform.WATCHOS_ARM32,
    Platform.WATCHOS_ARM64,
    Platform.WATCHOS_DEVICE_ARM64,
        -> system.family.isMac // All Apple devices can run if connected (but only launched from macOS)
}
