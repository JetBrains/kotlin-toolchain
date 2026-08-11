/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.jetbrains.apple.sdk.ArchitectureValue
import com.jetbrains.cidr.xcode.frameworks.AppleSdk
import com.jetbrains.cidr.xcode.frameworks.buildSystem.BuildSettingsResolver
import com.jetbrains.cidr.xcode.model.PBXBuildPhase
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.PBXTarget
import com.jetbrains.cidr.xcode.model.XCBuildConfiguration
import com.jetbrains.cidr.xcode.plist.Plist
import org.jetbrains.amper.frontend.Platform

internal const val IOS_PLATFORM_NAME = "iOS"
internal const val IOS_SIMULATOR_PLATFORM_NAME = "iOS Simulator"

internal fun Platform.toXcodePlatformTitle() = if (isIosSimulator) IOS_SIMULATOR_PLATFORM_NAME else IOS_PLATFORM_NAME

internal val Platform.isIosSimulator
    get() = when(this) {
        Platform.IOS_X64, Platform.IOS_SIMULATOR_ARM64 -> true
        else -> false
    }

internal val Platform.architecture
    get() = when (this) {
        Platform.IOS_ARM64 -> "arm64"
        Platform.IOS_X64 -> "x86_64"
        Platform.IOS_SIMULATOR_ARM64 -> "arm64"
        else -> error("Cannot determine apple architecture for $this")
    }

internal val Platform.sdk
    get() = when (this) {
        Platform.IOS_ARM64 -> "iphoneos"
        Platform.IOS_SIMULATOR_ARM64, Platform.IOS_X64 -> "iphonesimulator"
        else -> error("Cannot determine apple platform for $this")
    }

internal fun Map<String, *>.toPlist(): Plist = Plist().also { plist ->
    for ([k, v] in this) {
        @Suppress("UNCHECKED_CAST") // non-string keys will fail in toPlist() when used as Strings
        plist[k] = when (v) {
            is Map<*, *> -> (v as Map<String, *>).toPlist()
            else -> v
        }
    }
}

internal fun PBXBuildPhase.scriptText(): String? {
    if (type != PBXBuildPhase.Type.SHELL_SCRIPT) {
        return null
    }
    return (this["shellScript"] as? String).orEmpty()
}

internal class ConfigurationSettingsResolver(
    override val target: PBXTarget,
    override val buildConfiguration: XCBuildConfiguration,
) : BuildSettingsResolver() {
    override val projectFile: PBXProjectFile get() = target.file
    override val architectures: Set<ArchitectureValue>? get() = null
    override val sdk: AppleSdk? get() = null
    override fun areSdkAndArchitectureOverridden() = true
}
