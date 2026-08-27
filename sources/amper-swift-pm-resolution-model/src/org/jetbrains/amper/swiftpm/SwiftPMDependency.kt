package org.jetbrains.amper.swiftpm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.amper.serialization.paths.SerializablePath

/**
 * This is a serializable representation of a SwiftPM dependency that also matches the one from KGP
 */
@Serializable
sealed class SwiftPMDependency {
    abstract val packageName: String
    abstract val products: List<Product>
    abstract val cinteropClangModules: List<CinteropClangModule>
    abstract val traits: List<String>

    @Serializable
    data class Product(
        val name: String,
        val cinteropClangModules: List<String>,
        val platformConstraints: List<Platform>?
    )

    @Serializable
    data class CinteropClangModule(val name: String)

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
        @Serializable
        sealed class Version {
            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Exact")
            data class Exact(val value: String) : Version()

            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.From")
            data class From(val value: String) : Version()

            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Range")
            data class Range(val from: String, val through: String) : Version()

            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Branch")
            data class Branch(val value: String) : Version()

            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Version.Revision")
            data class Revision(val value: String) : Version()
        }

        @Serializable
        sealed class Repository {
            abstract val value: String

            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Repository.Id")
            data class Id(override val value: String) : Repository()

            @Serializable
            @SerialName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.SwiftPMDependency.Remote.Repository.Url")
            data class Url(override val value: String) : Repository()
        }
    }

    @Serializable
    data class Local(
        val absolutePath: SerializablePath,
        override val products: List<Product>,
        override val cinteropClangModules: List<CinteropClangModule>,
        override val packageName: String,
        override val traits: List<String>
    ) : SwiftPMDependency()
}