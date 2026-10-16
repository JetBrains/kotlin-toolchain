/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.attributes

/**
 * The Android build type a variant was built for.
 * It is set by the Android Gradle Plugin on libraries that publish several build types (e.g., debug and release).
 */
sealed class AndroidBuildType(override val value: String) : AttributeValue {
    companion object : Attribute<AndroidBuildType> {
        override val name: String = "com.android.build.api.attributes.BuildTypeAttr"

        override fun fromString(value: String): AndroidBuildType = when (value) {
            Debug.value -> Debug
            Release.value -> Release
            else -> Other(value)
        }
    }

    object Debug : AndroidBuildType("debug")
    object Release : AndroidBuildType("release")
    class Other(value: String) : AndroidBuildType(value)
}
