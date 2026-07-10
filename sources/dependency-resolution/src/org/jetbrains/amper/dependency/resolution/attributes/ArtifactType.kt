/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.attributes

sealed class ArtifactType(override val value: String) : AttributeValue {
    companion object : Attribute<ArtifactType> {
        override val name: String = "artifactType"

        override fun fromString(value: String): ArtifactType = when (value) {
            Jar.value -> Jar
            JavaClassesDirectory.value -> JavaClassesDirectory
            JavaResourcesDirectory.value -> JavaResourcesDirectory
            Zip.value -> Zip
            Directory.value -> Directory
            Binary.value -> Binary
            KLib.value -> KLib
            else -> Other(value)
        }
    }

    // Gradle built-in
    // since 4.0
    object Jar : ArtifactType("jar")
    object JavaClassesDirectory : ArtifactType("java-classes-directory")
    object JavaResourcesDirectory : ArtifactType("java-resources-directory")
    // since 5.3
    object Zip : ArtifactType("zip")
    object Directory : ArtifactType("directory")
    object Binary : ArtifactType("binary")

    // Custom
    object KLib : ArtifactType("org.jetbrains.kotlin.klib")
    class Other(value: String) : ArtifactType(value)
}