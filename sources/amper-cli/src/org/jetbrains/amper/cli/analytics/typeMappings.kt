/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.analytics

import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.fus.schema.FusBuildType
import org.jetbrains.amper.fus.schema.FusKotlinLeafPlatform
import org.jetbrains.amper.fus.schema.FusKtcProductType
import org.jetbrains.amper.fus.schema.FusTestFilterKind
import org.jetbrains.amper.stdlib.collections.mapToSet
import org.jetbrains.amper.test.FilterMode
import org.jetbrains.amper.test.TestFilter
import org.jetbrains.amper.util.BuildType

internal fun Platform.toFusPlatform(): FusKotlinLeafPlatform = when (this) {
    Platform.ANDROID_NATIVE,
    Platform.APPLE,
    Platform.COMMON,
    Platform.IOS,
    Platform.LINUX,
    Platform.MACOS,
    Platform.MINGW,
    Platform.NATIVE,
    Platform.TVOS,
    Platform.WATCHOS,
    Platform.WEB -> error("Not a leaf platform")
    Platform.JVM -> FusKotlinLeafPlatform.Jvm
    Platform.ANDROID -> FusKotlinLeafPlatform.Android
    Platform.JS -> FusKotlinLeafPlatform.Js
    Platform.WASM_JS -> FusKotlinLeafPlatform.WasmJs
    Platform.WASM_WASI -> FusKotlinLeafPlatform.WasmWasi
    Platform.LINUX_X64 -> FusKotlinLeafPlatform.LinuxX64
    Platform.LINUX_ARM64 -> FusKotlinLeafPlatform.LinuxArm64
    Platform.MACOS_X64 -> FusKotlinLeafPlatform.MacosX64
    Platform.MACOS_ARM64 -> FusKotlinLeafPlatform.MacosArm64
    Platform.TVOS_ARM64 -> FusKotlinLeafPlatform.TvosArm64
    Platform.TVOS_X64 -> FusKotlinLeafPlatform.TvosX64
    Platform.TVOS_SIMULATOR_ARM64 -> FusKotlinLeafPlatform.TvosSimulatorArm64
    Platform.IOS_ARM64 -> FusKotlinLeafPlatform.IosArm64
    Platform.IOS_SIMULATOR_ARM64 -> FusKotlinLeafPlatform.IosSimulatorArm64
    Platform.IOS_X64 -> FusKotlinLeafPlatform.IosX64
    Platform.WATCHOS_ARM64 -> FusKotlinLeafPlatform.WatchosArm64
    Platform.WATCHOS_ARM32 -> FusKotlinLeafPlatform.WatchosArm32
    Platform.WATCHOS_DEVICE_ARM64 -> FusKotlinLeafPlatform.WatchosDeviceArm64
    Platform.WATCHOS_SIMULATOR_ARM64 -> FusKotlinLeafPlatform.WatchosSimulatorArm64
    Platform.MINGW_X64 -> FusKotlinLeafPlatform.MingwX64
    Platform.ANDROID_NATIVE_ARM32 -> FusKotlinLeafPlatform.AndroidNativeArm32
    Platform.ANDROID_NATIVE_ARM64 -> FusKotlinLeafPlatform.AndroidNativeArm64
    Platform.ANDROID_NATIVE_X64 -> FusKotlinLeafPlatform.AndroidNativeX64
    Platform.ANDROID_NATIVE_X86 -> FusKotlinLeafPlatform.AndroidNativeX86
}

internal fun ProductType.toFusKtcProductType(): FusKtcProductType = when (this) {
    ProductType.JVM_APP -> FusKtcProductType.JvmApp
    ProductType.JVM_LIB -> FusKtcProductType.JvmLib
    ProductType.LIB,
    ProductType.KMP_LIB -> FusKtcProductType.KmpLib
    ProductType.JVM_AMPER_PLUGIN -> FusKtcProductType.JvmAmperPlugin
    ProductType.ANDROID_APP -> FusKtcProductType.AndroidApp
    ProductType.IOS_APP -> FusKtcProductType.IosApp
    ProductType.MACOS_APP -> FusKtcProductType.MacOSApp
    ProductType.LINUX_APP -> FusKtcProductType.LinuxApp
    ProductType.WINDOWS_APP -> FusKtcProductType.WindowsApp
    ProductType.WASM_JS_APP -> FusKtcProductType.WasmJSApp
    ProductType.WASM_WASI_APP -> FusKtcProductType.WasmWasiApp
    ProductType.JS_APP -> FusKtcProductType.JSApp
}

internal fun BuildType.toFusBuildType(): FusBuildType = when (this) {
    BuildType.Debug -> FusBuildType.Debug
    BuildType.Release -> FusBuildType.Release
}

internal fun List<TestFilter>.fusTestFilterKinds(): Set<FusTestFilterKind> = mapToSet {
    when (it) {
        is TestFilter.SpecificSuiteInclude -> FusTestFilterKind.IncludeClass
        is TestFilter.SpecificTestInclude -> FusTestFilterKind.IncludeTest
        is TestFilter.SuitePattern -> when (it.mode) {
            FilterMode.Include -> FusTestFilterKind.IncludeClassPattern
            FilterMode.Exclude -> FusTestFilterKind.ExcludeClassPattern
        }
        is TestFilter.TagExpression -> when (it.mode) {
            FilterMode.Include -> FusTestFilterKind.IncludeTag
            FilterMode.Exclude -> FusTestFilterKind.ExcludeTag
        }
    }
}
