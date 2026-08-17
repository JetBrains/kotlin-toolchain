/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlin.tooling.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The classifier of the tooling metadata artifact in the publication, as expected by its consumers.
 */
const val KOTLIN_TOOLING_METADATA_CLASSIFIER = "kotlin-tooling-metadata"

/**
 * The name of the file holding the tooling metadata, as expected by its consumers.
 */
const val KOTLIN_TOOLING_METADATA_FILE_NAME = "kotlin-tooling-metadata.json"

/**
 * The version of the tooling metadata format itself, not of the tool that produced it.
 */
const val KOTLIN_TOOLING_METADATA_SCHEMA_VERSION = "1.1.0"

// The target class names below are KGP implementation classes. Build systems other than Gradle have no equivalent of
// them, but they are part of the format that consumers of this file understand, so the matching ones must be reported.
const val KGP_METADATA_TARGET = "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget"
const val KGP_JVM_TARGET = "org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget"
const val KGP_ANDROID_TARGET = "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget"
const val KGP_JS_IR_TARGET = "org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget"
const val KGP_NATIVE_TARGET = "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget"

/**
 * Describes how a multiplatform library was built, and which platforms it was built for.
 *
 * This is published next to the root artifact of a multiplatform library with the
 * [KOTLIN_TOOLING_METADATA_CLASSIFIER] classifier and the `json` extension, the same way KGP publishes it.
 *
 * See https://kotlinlang.org/docs/multiplatform-publish-lib.html and the `KotlinToolingMetadata` format in KGP.
 */
@Serializable
data class KotlinToolingMetadata(
    val schemaVersion: String,
    val buildSystem: String,
    val buildSystemVersion: String,
    val buildPlugin: String,
    val buildPluginVersion: String,
    val projectSettings: ProjectSettings,
    val projectTargets: List<ProjectTarget>,
)

@Serializable
data class ProjectSettings(
    val isHmppEnabled: Boolean,
    val isCompatibilityMetadataVariantEnabled: Boolean,
    val isKPMEnabled: Boolean,
)

@Serializable
data class ProjectTarget(
    val target: String,
    val platformType: String,
    val extras: TargetExtras? = null,
)

/**
 * Platform-specific details of a target. Only the entry matching the target's platform type is set.
 */
@Serializable
data class TargetExtras(
    val android: AndroidExtras? = null,
    val jvm: JvmExtras? = null,
    val js: JsExtras? = null,
    val native: NativeExtras? = null,
)

@Serializable
data class AndroidExtras(
    val sourceCompatibility: String,
    val targetCompatibility: String,
)

@Serializable
data class JvmExtras(
    val jvmTarget: String,
    val withJavaEnabled: Boolean,
)

@Serializable
data class JsExtras(
    val isBrowserConfigured: Boolean,
    val isNodejsConfigured: Boolean,
)

@Serializable
data class NativeExtras(
    val konanTarget: String,
    val konanVersion: String,
    val konanAbiVersion: String,
)

/**
 * Serializes this tooling metadata in the JSON representation that the consumers of the format expect.
 */
fun KotlinToolingMetadata.serialize(): String = json.encodeToString(this)

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    prettyPrintIndent = "  "
}
