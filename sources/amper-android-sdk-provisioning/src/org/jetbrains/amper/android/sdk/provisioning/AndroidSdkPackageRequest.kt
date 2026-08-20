/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.sdk.provisioning

import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.annotations.Nls

/**
 * Describes an Android SDK package to provision.
 */
sealed interface AndroidSdkPackageRequest {

    /**
     * User-readable description of the request.
     */
    val displayName: @Nls String

    data class CommandLineTools(val version: String) : AndroidSdkPackageRequest {
        override val displayName: @Nls String
            get() = AndroidSdkProvisioningBundle.message("commandline.tools.display.name", version)
    }

    data object PlatformTools : AndroidSdkPackageRequest {
        override val displayName: @Nls String
            get() = AndroidSdkProvisioningBundle.message("platform.tools.display.name")
    }

    data object Emulator : AndroidSdkPackageRequest {
        override val displayName: @Nls String
            get() = AndroidSdkProvisioningBundle.message("emulator.display.name")
    }

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

        override val displayName: @Nls String
            get() = AndroidSdkProvisioningBundle.message(
                "platform.display.name",
                platformVersionString(apiLevel, minorApiLevel, sdkExtension),
            )
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

        override val displayName: @Nls String
            get() = AndroidSdkProvisioningBundle.message(
                "platform.sources.display.name",
                platformVersionString(apiLevel, minorApiLevel),
            )
    }

    data class BuildTools(val version: String) : AndroidSdkPackageRequest {
        override val displayName: @Nls String
            get() = AndroidSdkProvisioningBundle.message("build.tools.display.name", version)
    }

    /**
     * System image provisioning tries to avoid downloading (as those images are pretty big),
     * so any local image that is __at least__ [minimalAcceptableApiLevel] will be preferred to downloading.
     *
     * If no local images matching this criterion were found, the __latest stable__ system image will be downloaded
     * as it's the one that can be later reused in most projects.
     */
    data class SystemImage(
        val minimalAcceptableApiLevel: Int,
        val tag: ServicesTag,
        val abi: ImageAbi,
    ) : AndroidSdkPackageRequest {
        init {
            require(minimalAcceptableApiLevel >= 1) { "Android API level must be positive" }
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

        override val displayName: @Nls String
            get() = AndroidSdkProvisioningBundle.message(
                "system.image.display.name",
                when (abi) {
                    ImageAbi.X86_64 -> "x86"
                    ImageAbi.Arm64V8A -> "ARM64"
                },
                minimalAcceptableApiLevel.toString(),
                when (tag) {
                    ServicesTag.GoogleApis -> "Google APIs"
                },
            )
    }
}

private fun platformVersionString(
    apiLevel: Int,
    minorApiLevel: Int,
    sdkExtension: Int? = null,
): String = buildString {
    append(apiLevel)
    if (apiLevel >= 37 || minorApiLevel != 0) {
        // Minor API level 0 started being published at API 37. API 36 has only 36 and 36.1.
        append(".$minorApiLevel")
    }
    sdkExtension?.let { append("-ext$it") }
}
