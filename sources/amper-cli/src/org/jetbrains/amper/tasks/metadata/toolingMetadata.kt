/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.tasks.rootFragment
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.div

private val logger = LoggerFactory.getLogger("kotlin-tooling-metadata")

/**
 * The classifier of the tooling metadata artifact in the publication, as expected by its consumers.
 */
internal const val KOTLIN_TOOLING_METADATA_CLASSIFIER = "kotlin-tooling-metadata"

private const val KOTLIN_TOOLING_METADATA_FILE_NAME = "kotlin-tooling-metadata.json"

/**
 * The version of the tooling metadata format itself, not of the tool that produced it.
 */
private const val SCHEMA_VERSION = "1.1.0"

/**
 * Where KGP reports the Kotlin Gradle plugin, the Kotlin Toolchain has no build plugin at all: multiplatform support
 * is built into the build system itself. We therefore report the toolchain in both fields, with its own version.
 */
private const val BUILD_SYSTEM = "Kotlin Toolchain"

private const val BUILD_PLUGIN = BUILD_SYSTEM

/**
 * The name of the entry holding the manifest of a klib, and the property of that manifest holding the ABI version of
 * the compiler that produced it (which is what the tooling metadata calls the Kotlin/Native ABI version).
 */
private const val KLIB_MANIFEST_ENTRY = "default/manifest"
private const val KLIB_ABI_VERSION_PROPERTY = "abi_version"

// The target class names below are KGP implementation classes. They have no equivalent in the Kotlin Toolchain, but
// they are part of the format that consumers of this file understand, so we report the ones matching our targets.
private const val KGP_METADATA_TARGET = "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget"
private const val KGP_JVM_TARGET = "org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget"
private const val KGP_ANDROID_TARGET = "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget"
private const val KGP_JS_IR_TARGET = "org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget"
private const val KGP_NATIVE_TARGET = "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget"

/**
 * Describes how a multiplatform library was built, and which platforms it was built for.
 *
 * This is published next to the root artifact of a multiplatform library with the
 * [KOTLIN_TOOLING_METADATA_CLASSIFIER] classifier and the `json` extension, the same way KGP publishes it.
 *
 * See https://kotlinlang.org/docs/multiplatform-publish-lib.html and the `KotlinToolingMetadata` format in KGP.
 */
@Serializable
internal data class KotlinToolingMetadata(
    val schemaVersion: String,
    val buildSystem: String,
    val buildSystemVersion: String,
    val buildPlugin: String,
    val buildPluginVersion: String,
    val projectSettings: ProjectSettings,
    val projectTargets: List<ProjectTarget>,
)

@Serializable
internal data class ProjectSettings(
    val isHmppEnabled: Boolean,
    val isCompatibilityMetadataVariantEnabled: Boolean,
    val isKPMEnabled: Boolean,
)

@Serializable
internal data class ProjectTarget(
    val target: String,
    val platformType: String,
    val extras: TargetExtras? = null,
)

/**
 * Platform-specific details of a target. Only the entry matching the target's platform type is set.
 */
@Serializable
internal data class TargetExtras(
    val android: AndroidExtras? = null,
    val jvm: JvmExtras? = null,
    val js: JsExtras? = null,
    val native: NativeExtras? = null,
)

@Serializable
internal data class AndroidExtras(
    val sourceCompatibility: String,
    val targetCompatibility: String,
)

@Serializable
internal data class JvmExtras(
    val jvmTarget: String,
    val withJavaEnabled: Boolean,
)

@Serializable
internal data class JsExtras(
    val isBrowserConfigured: Boolean,
    val isNodejsConfigured: Boolean,
)

@Serializable
internal data class NativeExtras(
    val konanTarget: String,
    val konanVersion: String,
    val konanAbiVersion: String,
)

/**
 * Describes the given multiplatform [module] as tooling metadata.
 *
 * The [konanAbiVersion] is the ABI version of the Kotlin/Native compiler that produced the klibs of this module, as
 * read by [readKlibAbiVersion]. Native targets report no extras at all when it is null, because the format requires
 * this value as part of them.
 *
 * This is a pure function of its arguments, so the result can be used as an input of an incremental cache.
 */
internal fun kotlinToolingMetadataFor(module: AmperModule, konanAbiVersion: String?): KotlinToolingMetadata {
    // The Kotlin version is platform-agnostic in the schema, so any fragment reports the same one.
    val kotlinVersion = module.rootFragment.settings.kotlin.version

    val platformTargets = module.leafFragments
        .filterNot { it.isTest }
        // sorted so that the contents of the published file are reproducible
        .sortedBy { it.platform.pretty }
        .map { it.toProjectTarget(kotlinVersion, konanAbiVersion) }

    return KotlinToolingMetadata(
        schemaVersion = SCHEMA_VERSION,
        buildSystem = BUILD_SYSTEM,
        buildSystemVersion = AmperBuild.mavenVersion,
        buildPlugin = BUILD_PLUGIN,
        buildPluginVersion = AmperBuild.mavenVersion,
        projectSettings = ProjectSettings(
            // The Kotlin Toolchain only supports the hierarchical project structure and publishes neither the legacy
            // compatibility metadata variant nor the Kotlin Project Model.
            isHmppEnabled = true,
            isCompatibilityMetadataVariantEnabled = false,
            isKPMEnabled = false,
        ),
        // The metadata target represents the common part of the library, which is always published for a
        // multiplatform library.
        projectTargets = platformTargets + ProjectTarget(target = KGP_METADATA_TARGET, platformType = "common"),
    )
}

private fun LeafFragment.toProjectTarget(kotlinVersion: String, konanAbiVersion: String?): ProjectTarget = when {
    platform == Platform.JVM -> ProjectTarget(
        target = KGP_JVM_TARGET,
        platformType = "jvm",
        // Java sources are compiled as part of the JVM fragment itself, so there is no equivalent of KGP's
        // 'withJava()' to report here.
        extras = jvmRelease()?.let { TargetExtras(jvm = JvmExtras(jvmTarget = it, withJavaEnabled = false)) },
    )
    platform == Platform.ANDROID -> ProjectTarget(
        target = KGP_ANDROID_TARGET,
        platformType = "androidJvm",
        extras = jvmRelease()?.let {
            TargetExtras(android = AndroidExtras(sourceCompatibility = it, targetCompatibility = it))
        },
    )
    platform == Platform.JS -> ProjectTarget(
        target = KGP_JS_IR_TARGET,
        platformType = "js",
        extras = TargetExtras(js = webExtras()),
    )
    platform == Platform.WASM_JS || platform == Platform.WASM_WASI -> ProjectTarget(
        target = KGP_JS_IR_TARGET,
        platformType = "wasm",
        extras = TargetExtras(js = webExtras()),
    )
    platform.isDescendantOf(Platform.NATIVE) -> ProjectTarget(
        target = KGP_NATIVE_TARGET,
        platformType = "native",
        // The Kotlin/Native distribution is downloaded as 'kotlin-native-prebuilt' in the configured Kotlin version
        // (see downloadNativeCompiler), so the version of the Kotlin/Native compiler is the Kotlin version.
        extras = konanAbiVersion?.let {
            TargetExtras(
                native = NativeExtras(
                    konanTarget = platform.nameForCompiler,
                    konanVersion = kotlinVersion,
                    konanAbiVersion = it,
                ),
            )
        },
    )
    else -> error("Unsupported platform for tooling metadata: ${platform.pretty}")
}

/**
 * The JVM release of this fragment in the notation used by the Kotlin and Java compilers ('1.8', '17', '21', …),
 * or null if no release is enforced.
 */
private fun LeafFragment.jvmRelease(): String? = settings.jvm.release?.let {
    if (it <= 8) "1.$it" else it.toString()
}

// Browser and Node.js are launch targets of applications, which a published library doesn't configure.
private fun webExtras() = JsExtras(isBrowserConfigured = false, isNodejsConfigured = false)

/**
 * Reads the ABI version of the compiler that produced the given [klib] from its manifest or returns null if the
 * manifest cannot be read or doesn't mention it.
 *
 * This is the value that the tooling metadata reports as the Kotlin/Native ABI version of native targets. It is
 * versioned separately from the compiler itself, so it cannot be derived from the Kotlin version.
 */
internal suspend fun readKlibAbiVersion(klib: Path): String? = withContext(Dispatchers.IO) {
    try {
        ZipFile(klib.toFile()).use { zip ->
            val manifestEntry = zip.getEntry(KLIB_MANIFEST_ENTRY) ?: return@use null
            val manifest = Properties().apply { zip.getInputStream(manifestEntry).use { load(it) } }
            manifest.getProperty(KLIB_ABI_VERSION_PROPERTY)
        }
    } catch (e: IOException) {
        logger.warn("Cannot read the ABI version from the klib at '$klib', it won't be reported in the tooling metadata", e)
        null
    }
}

/**
 * Writes the given tooling [metadata] to a `kotlin-tooling-metadata.json` file in the [outputDir], and returns its path.
 */
internal suspend fun writeKotlinToolingMetadata(metadata: KotlinToolingMetadata, outputDir: Path): Path {
    outputDir.createDirectories()

    val toolingMetadataPath = outputDir / KOTLIN_TOOLING_METADATA_FILE_NAME

    withContext(Dispatchers.IO) {
        Files.writeString(
            toolingMetadataPath,
            json.encodeToString(metadata),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    return toolingMetadataPath
}
