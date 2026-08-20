/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.attributes

import org.jetbrains.amper.dependency.resolution.PlatformType
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope

sealed class Usage(override val value: String) : AttributeValue {
    companion object : Attribute<Usage> {
        override val name: String = "org.gradle.usage"

        override fun fromString(value: String): Usage = when (value) {
            JavaApi.value -> JavaApi
            JavaRuntime.value -> JavaRuntime
            KotlinApi.value -> KotlinApi
            KotlinRuntime.value -> KotlinRuntime
            KotlinMetadata.value -> KotlinMetadata
            KotlinMultiplatformResources.value -> KotlinMultiplatformResources
            KotlinMultiplatformResourcesJs.value -> KotlinMultiplatformResourcesJs
            else -> Other(value)
        }

        fun fromPlatformAndScope(platform: ResolutionPlatform, scope: ResolutionScope): Usage {
            return when(platform.type) {
                PlatformType.COMMON -> KotlinMetadata
                PlatformType.JVM, PlatformType.ANDROID_JVM -> {
                    if (scope == ResolutionScope.COMPILE) JavaApi else JavaRuntime
                }
                PlatformType.WASM, PlatformType.JS, PlatformType.NATIVE -> {
                    if (scope == ResolutionScope.COMPILE) KotlinApi else KotlinRuntime
                }
            }
        }

        /**
         * A usage of the variant KMP resources of the given [platform] are published in,
         * or `null` if resources are not published as a separate variant for that platform
         * (resources of JVM and Android targets are packed into the main artifact).
         */
        fun kmpResourcesUsage(platform: ResolutionPlatform): Usage? = when (platform.type) {
            PlatformType.NATIVE -> KotlinMultiplatformResources
            PlatformType.JS, PlatformType.WASM -> KotlinMultiplatformResourcesJs
            PlatformType.JVM, PlatformType.ANDROID_JVM, PlatformType.COMMON -> null
        }
    }

    object JavaApi : Usage("java-api")
    object JavaRuntime : Usage("java-runtime")
    object KotlinApi : Usage("kotlin-api")
    object KotlinRuntime : Usage("kotlin-runtime")
    object KotlinMetadata : Usage("kotlin-metadata")

    /**
     * KMP resources of a native target, see [kmpResourcesUsage].
     */
    object KotlinMultiplatformResources : Usage("kotlin-multiplatformresources")

    /**
     * KMP resources of a JS or a Wasm target, see [kmpResourcesUsage].
     */
    object KotlinMultiplatformResourcesJs : Usage("kotlin-multiplatformresourcesjs")

    class Other(value: String) : Usage(value)

    fun isApi(): Boolean = value.endsWith("-api")
    fun isRuntime(): Boolean = value.endsWith("-runtime")
}
