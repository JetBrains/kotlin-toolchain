/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.kotlin.tooling.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

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

/**
 * Details of a JVM target.
 */
@Serializable
data class JvmExtras(
    /**
     * The JVM bytecode version targeted by the Kotlin compiler, in the notation used by the Kotlin and Java compilers
     * ('1.8', '17', '21', …).
     */
    val jvmTarget: String,
    /**
     * Whether the deprecated `withJava()` was called on the KGP `KotlinJvmTarget` this metadata describes.
     *
     * This does **not** tell whether the library has Java sources, nor whether any Java source was compiled into the
     * JVM target. It only reports a legacy Gradle-specific opt-in: `withJava()` pulls the Gradle Java plugin's source
     * sets (`src/main/java` and `src/test/java`) into the compilations of the JVM target, and disables the Java
     * plugin's own `jar` and `test` tasks in favour of the target's equivalents. KGP fills this field straight from
     * `KotlinJvmTarget.withJavaEnabled`, a property that nothing but `withJava()` sets.
     *
     * A `false` value therefore says nothing about Java sources, and it is what a Gradle build reports as soon as it
     * doesn't use that legacy wiring. KGP has deprecated `withJava()` altogether (as of 2.2.10, with the message
     * "Kotlin Multiplatform JVM target compiles Java sources by default. Please remove `withJava()` call."), so even
     * Gradle builds that do compile Java sources into their JVM target now report `false` here. Build systems that
     * compile Java sources without any such opt-in report `false` for the same reason.
     */
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

/**
 * Writes the given [KotlinToolingMetadata] to a [KOTLIN_TOOLING_METADATA_FILE_NAME] file in the [outputDir], and returns
 * its path.
 */
fun KotlinToolingMetadata.writeTo(outputDir: Path): Path {
    outputDir.createDirectories()

    val toolingMetadataPath = outputDir / KOTLIN_TOOLING_METADATA_FILE_NAME

    toolingMetadataPath.writeText(serialize())

    return toolingMetadataPath.also { it.writeText(serialize()) }
}