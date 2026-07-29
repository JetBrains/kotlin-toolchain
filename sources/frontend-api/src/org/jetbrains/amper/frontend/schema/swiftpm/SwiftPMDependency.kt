/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.swiftpm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.amper.serialization.paths.SerializablePath

@Serializable
sealed class SwiftPMDependency {
    abstract val packageName: String
    abstract val products: List<Product>
    abstract val cinteropClangModules: List<CinteropClangModule>
    abstract val traits: List<String>

    /** Describes a SwiftPM product imported from a package. */
    @Serializable
    data class Product(
        val name: String,
        val cinteropClangModules: List<String>,
        val platformConstraints: List<Platform>?
    ) {
    }

    /** Describes a Clang module imported into cinterop from a SwiftPM package. */
    @Serializable
    data class CinteropClangModule(val name: String) {
    }

    /** Apple platform constraint used by [Product] and [CinteropClangModule]. */
    @Serializable
    enum class Platform {
        iOS,
        macOS,
        tvOS,
        watchOS;

        val swiftEnumName: String get() = when (this) {
            iOS -> "iOS"
            macOS -> "macOS"
            tvOS -> "tvOS"
            watchOS -> "watchOS"
        }
    }

    /** A remote SwiftPM package dependency created by [SwiftPMImportExtension.swiftPackage]. */
    @Serializable
    @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote")
    data class Remote(
        val repository: Repository,
        val version: Version,
        override val products: List<Product>,
        override val cinteropClangModules: List<CinteropClangModule>,
        override val packageName: String,
        override val traits: List<String>,
    ) : SwiftPMDependency() {

        /** Version requirement used when resolving a remote SwiftPM package. */
        @Serializable
        sealed class Version {
            /** Pins the package to an exact version. Prefer range-based requirements when possible. */
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Exact")
            data class Exact(val value: String) : Version()

            /** Allows versions from the given lower bound up to, but not including, the next major version. */
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.From")
            data class From(val value: String) : Version()

            /** Allows versions in the inclusive range from [from] to [through]. */
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Range")
            data class Range(val from: String, val through: String) : Version()

            /** Resolves the package from the given Git branch. */
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Branch")
            data class Branch(val value: String) : Version()

            /** Resolves the package from the given Git revision. */
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Revision")
            data class Revision(val value: String) : Version()
        }

        /** Remote repository descriptor used to locate a SwiftPM package. */
        @Serializable
        sealed class Repository {
            /** SwiftPM package identity. */
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Repository.Id")
            data class Id(val value: String) : Repository()

            /** SwiftPM package repository URL. */
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Repository.Url")
            data class Url(val value: String) : Repository()
        }

        companion object {
        }
    }

    /**
     * A local SwiftPM package dependency created by [SwiftPMImportExtension.localSwiftPackage].
     *
     * @property absolutePath Absolute path to the SwiftPM package directory
     */
    @Serializable
    data class Local(
        val absolutePath: SerializablePath,
        override val products: List<Product>,
        override val cinteropClangModules: List<CinteropClangModule>,
        override val packageName: String,
        override val traits: List<String>
    ) : SwiftPMDependency() {
    }
}