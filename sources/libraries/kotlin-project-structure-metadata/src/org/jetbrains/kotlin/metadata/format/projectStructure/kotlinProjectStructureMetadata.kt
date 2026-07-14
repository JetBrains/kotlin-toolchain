/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlin.metadata.format.projectStructure

import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.metadata.format.json

fun String.parseKmpLibraryMetadata(): KotlinProjectStructureMetadata = json.decodeFromString(this)

fun KotlinProjectStructureMetadata.serialize(): String = json.encodeToString(this)


@Serializable
data class KotlinProjectStructureMetadata(
    val projectStructure: ProjectStructure,
)

@Serializable
data class ProjectStructure(
    val formatVersion: String,
    val isPublishedAsRoot: String,
    val variants: List<Variant>,
    val sourceSets: List<SourceSet>,
)

@Serializable
data class Variant(
    val name: String,
    val sourceSet: List<String>,
)

@Serializable
data class SourceSet(
    val name: String,
    val dependsOn: List<String>,
    val moduleDependency: List<String>,
    val sourceSetCInteropMetadataDirectory: String? = null,
    val binaryLayout: String? = null,
    val hostSpecific: String? = null,
)
