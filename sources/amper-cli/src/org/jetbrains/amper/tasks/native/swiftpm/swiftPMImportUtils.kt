/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation

data class XcodebuildPlatform(
    val destination: String,
    val sdk: String,
)

internal val Platform.xcodebuildPlatform: XcodebuildPlatform
    get() = when (this) {
        Platform.IOS_X64,
        Platform.IOS_SIMULATOR_ARM64 -> XcodebuildPlatform(
            destination = "iOS Simulator",
            sdk = "iphonesimulator",
        )
        Platform.IOS_ARM64 -> XcodebuildPlatform(
            destination = "iOS",
            sdk = "iphoneos",
        )
        Platform.MACOS_ARM64,
        Platform.MACOS_X64 -> XcodebuildPlatform(
            destination = "macOS",
            sdk = "macosx",
        )
        Platform.WATCHOS_SIMULATOR_ARM64 -> XcodebuildPlatform(
            destination = "watchOS Simulator",
            sdk = "watchsimulator",
        )
        Platform.WATCHOS_ARM32,
        Platform.WATCHOS_ARM64,
        Platform.WATCHOS_DEVICE_ARM64 -> XcodebuildPlatform(
            destination = "watchOS",
            sdk = "watchos",
        )
        Platform.TVOS_X64,
        Platform.TVOS_SIMULATOR_ARM64 -> XcodebuildPlatform(
            destination = "tvOS Simulator",
            sdk = "appletvsimulator",
        )
        Platform.TVOS_ARM64 -> XcodebuildPlatform(
            destination = "tvOS",
            sdk = "appletvos",
        )

        Platform.COMMON,
        Platform.JVM,
        Platform.ANDROID,
        Platform.WEB,
        Platform.JS,
        Platform.WASM_JS,
        Platform.WASM_WASI,
        Platform.NATIVE,
        Platform.LINUX,
        Platform.LINUX_X64,
        Platform.LINUX_ARM64,
        Platform.APPLE,
        Platform.MACOS,
        Platform.TVOS,
        Platform.IOS,
        Platform.WATCHOS,
        Platform.MINGW,
        Platform.MINGW_X64,
        Platform.ANDROID_NATIVE,
        Platform.ANDROID_NATIVE_ARM32,
        Platform.ANDROID_NATIVE_ARM64,
        Platform.ANDROID_NATIVE_X64,
        Platform.ANDROID_NATIVE_X86 -> error("$this is not a leaf Apple platform")
    }

internal val Platform.clangArch: String
    get() = when (this) {
        Platform.TVOS_X64,
        Platform.MACOS_X64,
        Platform.IOS_X64 -> "x86_64"
        Platform.TVOS_ARM64,
        Platform.TVOS_SIMULATOR_ARM64,
        Platform.WATCHOS_DEVICE_ARM64,
        Platform.WATCHOS_SIMULATOR_ARM64,
        Platform.MACOS_ARM64,
        Platform.IOS_ARM64,
        Platform.IOS_SIMULATOR_ARM64 -> "arm64"
        Platform.WATCHOS_ARM64 -> "arm64_32"
        Platform.WATCHOS_ARM32 -> "armv7k"

        Platform.COMMON,
        Platform.JVM,
        Platform.ANDROID,
        Platform.WEB,
        Platform.JS,
        Platform.WASM_JS,
        Platform.WASM_WASI,
        Platform.NATIVE,
        Platform.LINUX,
        Platform.LINUX_X64,
        Platform.LINUX_ARM64,
        Platform.APPLE,
        Platform.MACOS,
        Platform.TVOS,
        Platform.IOS,
        Platform.WATCHOS,
        Platform.MINGW,
        Platform.MINGW_X64,
        Platform.ANDROID_NATIVE,
        Platform.ANDROID_NATIVE_ARM32,
        Platform.ANDROID_NATIVE_ARM64,
        Platform.ANDROID_NATIVE_X64,
        Platform.ANDROID_NATIVE_X86 -> error("$this is not a leaf Apple platform")
    }

internal fun AmperModule.hasDirectSwiftPMDependencies(): Boolean = leafAppleFragments().any { fragment ->
    fragment.externalDependencies.any { it is RemoteSwiftPMDependencyNotation || it is LocalSwiftPMDependencyNotation }
}