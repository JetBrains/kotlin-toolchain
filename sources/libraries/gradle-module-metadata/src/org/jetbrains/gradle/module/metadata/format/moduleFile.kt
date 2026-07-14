/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.gradle.module.metadata.format

import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.metadata.format.json

/**
 * [Gradle Module Metadata specification](https://github.com/gradle/gradle/blob/master/platforms/documentation/docs/src/docs/design/gradle-module-metadata-latest-specification.md)
 */
fun String.parseMetadata(): Module = json.decodeFromString(this)
fun Module.serialize(): String = json.encodeToString(this)

@Serializable
data class Module(
    val formatVersion: String,
    val component: Component,
    val createdBy: CreatedBy? = null,
    val variants: List<Variant> = emptyList(),
)

@Serializable
data class Component(
    val url: String? = null,
    val group: String,
    val module: String,
    val version: String,
    val attributes: Map<String, String> = mapOf(),
)

@Serializable
data class CreatedBy(
    val gradle: Gradle? = null,
    val maven: Maven? = null,
    val kotlinToolchain: KotlinToolchain? = null,
)

@Serializable
data class Gradle(
    val version: String,
    val buildId: String? = null,
)

@Serializable
data class Maven(
    val version: String,
    val buildId: String? = null,
)

@Serializable
data class KotlinToolchain(
    val version: String,

)

@Serializable
data class Variant(
    val name: String,
    val attributes: Map<String, String> = mapOf(),
    val dependencies: List<Dependency> = emptyList(),
    val dependencyConstraints: List<Dependency> = emptyList(),
    val files: List<File> = emptyList(),
    val `available-at`: AvailableAt? = null,
    val capabilities: List<Capability> = emptyList(),
)

@Serializable
data class Dependency(
    val group: String,
    val module: String,
    val version: Version? = null,
    val attributes: Map<String, String> = mapOf(),
    val endorseStrictVersions: Boolean? = null,
    val reason: String? = null,
    val thirdPartyCompatibility: ThirdPartyCompatibility? = null
)

@Serializable
data class ThirdPartyCompatibility (
    val artifactSelector: ArtifactSelector? = null,
)

@Serializable
data class ArtifactSelector (
    val name: String? = null,
    val type: String? = null,
    val extension: String? = null,
    val classifier: String? = null,
)

@Serializable
data class AvailableAt(
    val url: String,
    val group: String,
    val module: String,
    val version: String
)

@Serializable
data class Capability(
    val group: String,
    val name: String,
    val version: String,
)

@Serializable
data class Version(
    val strictly: String? = null,
    val requires: String? = null,
    val prefers: String? = null,
)

@Serializable
data class File(
    val name: String,
    val url: String,
    val size: Long? = null,
    val sha512: String? = null,
    val sha256: String? = null,
    val sha1: String? = null,
    val md5: String? = null,
)
