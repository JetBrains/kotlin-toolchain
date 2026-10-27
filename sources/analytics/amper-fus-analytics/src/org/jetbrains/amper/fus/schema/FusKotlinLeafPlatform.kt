/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

enum class FusKotlinLeafPlatform(override val fusId: String) : FusEnum<FusKotlinLeafPlatform> {
    Jvm("jvm"),
    Android("android"),

    Js("js"),
    WasmJs("wasm_js"),
    WasmWasi("wasm_wasi"),

    LinuxX64("linux_x64"),
    LinuxArm64("linux_arm64"),

    MacosX64("macos_x64"),
    MacosArm64("macos_arm64"),

    TvosArm64("tvos_arm64"),
    TvosX64("tvos_x64"),
    TvosSimulatorArm64("tvos_simulator_arm64"),

    IosArm64("ios_arm64"),
    IosSimulatorArm64("ios_simulator_arm64"),
    IosX64("ios_x64"),

    WatchosArm64("watchos_arm64"),
    WatchosArm32("watchos_arm32"),
    WatchosDeviceArm64("watchos_device_arm64"),
    WatchosSimulatorArm64("watchos_simulator_arm64"),

    MingwX64("mingw_x64"),

    AndroidNativeArm32("android_native_arm32"),
    AndroidNativeArm64("android_native_arm64"),
    AndroidNativeX64("android_native_x64"),
    AndroidNativeX86("android_native_x86"),
}
