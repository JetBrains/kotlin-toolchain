/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

enum class FusKtcProductType(override val fusId: String) : FusEnum<FusKtcProductType> {
    AndroidApp("android/app"),
    IosApp("ios/app"),
    JSApp("js/app"),
    JvmAmperPlugin("jvm/amper-plugin"),
    JvmApp("jvm/app"),
    JvmLib("jvm/lib"),
    KmpLib("kmp/lib"),
    LinuxApp("linux/app"),
    MacOSApp("macos/app"),
    WasmJSApp("wasm-js/app"),
    WasmWasiApp("wasm-wasi/app"),
    WindowsApp("windows/app"),
}
