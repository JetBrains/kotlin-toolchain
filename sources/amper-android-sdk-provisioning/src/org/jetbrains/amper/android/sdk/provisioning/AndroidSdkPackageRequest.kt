/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import org.jetbrains.amper.core.UsedInIdePlugin

/**
 * Describes an Android SDK package to provision.
 */
sealed interface AndroidSdkPackageRequest {

    data class CommandLineTools(val version: String) : AndroidSdkPackageRequest

    data object PlatformTools : AndroidSdkPackageRequest

    data object Emulator : AndroidSdkPackageRequest

    @UsedInIdePlugin
    data class Platform(
        val apiLevel: Int,
        val minorApiLevel: Int,
        val sdkExtension: Int? = null,
    ) : AndroidSdkPackageRequest {
        init {
            require(apiLevel >= 1) { "Android API level must be positive" }
            require(minorApiLevel >= 0) { "Android minor API level must not be negative" }
            require(sdkExtension == null || sdkExtension >= 0) { "Android SDK extension must not be negative" }
        }
    }

    @UsedInIdePlugin
    data class PlatformSources(
        val apiLevel: Int,
        val minorApiLevel: Int,
    ) : AndroidSdkPackageRequest {
        init {
            require(apiLevel >= 1) { "Android API level must be positive" }
            require(minorApiLevel >= 0) { "Android minor API level must not be negative" }
        }
    }

    data class BuildTools(val version: String) : AndroidSdkPackageRequest

    data class SystemImage(
        val apiLevel: Int,
        val tag: ServicesTag,
        val abi: ImageAbi,
    ) : AndroidSdkPackageRequest {
        init {
            require(apiLevel >= 1) { "Android API level must be positive" }
        }

        enum class ServicesTag(val value: String) {
            GoogleApis("google_apis"),
        }

        /**
         * CPU architectures published for Android system images.
         */
        enum class ImageAbi(internal val repositoryValue: String) {
            X86_64("x86_64"),
            Arm64V8A("arm64-v8a"),
        }
    }
}
