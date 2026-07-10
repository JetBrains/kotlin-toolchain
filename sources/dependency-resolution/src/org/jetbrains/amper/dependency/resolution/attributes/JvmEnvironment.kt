/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.attributes

import org.jetbrains.amper.dependency.resolution.PlatformType.ANDROID_JVM
import org.jetbrains.amper.dependency.resolution.PlatformType.JVM
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform

sealed class JvmEnvironment(override val value: String) : AttributeValue {
    companion object : Attribute<JvmEnvironment> {
        override val name: String = "org.gradle.jvm.environment"

        override fun fromString(value: String): JvmEnvironment = when (value) {
            StandardJvm.value -> StandardJvm
            Android.value -> Android
            NonJvm.value -> NonJvm
            else -> Other(value)
        }

        fun fromPlatform(platform: ResolutionPlatform): JvmEnvironment {
            return when (platform.type) {
                JVM -> StandardJvm
                ANDROID_JVM -> Android
                else -> NonJvm
            }
        }
    }

    object StandardJvm : JvmEnvironment("standard-jvm")
    object Android : JvmEnvironment("android")
    object NonJvm : JvmEnvironment("non-jvm")
    class Other(value: String) : JvmEnvironment(value)
}
