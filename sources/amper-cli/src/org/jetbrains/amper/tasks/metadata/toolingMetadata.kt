/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.tasks.rootFragment
import org.jetbrains.kotlin.tooling.metadata.AndroidExtras
import org.jetbrains.kotlin.tooling.metadata.JsExtras
import org.jetbrains.kotlin.tooling.metadata.JvmExtras
import org.jetbrains.kotlin.tooling.metadata.KGP_ANDROID_TARGET
import org.jetbrains.kotlin.tooling.metadata.KGP_JS_IR_TARGET
import org.jetbrains.kotlin.tooling.metadata.KGP_JVM_TARGET
import org.jetbrains.kotlin.tooling.metadata.KGP_METADATA_TARGET
import org.jetbrains.kotlin.tooling.metadata.KGP_NATIVE_TARGET
import org.jetbrains.kotlin.tooling.metadata.KOTLIN_TOOLING_METADATA_SCHEMA_VERSION
import org.jetbrains.kotlin.tooling.metadata.KotlinToolingMetadata
import org.jetbrains.kotlin.tooling.metadata.NativeExtras
import org.jetbrains.kotlin.tooling.metadata.ProjectSettings
import org.jetbrains.kotlin.tooling.metadata.ProjectTarget
import org.jetbrains.kotlin.tooling.metadata.TargetExtras
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipFile

private val logger = LoggerFactory.getLogger("kotlin-tooling-metadata")

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
        schemaVersion = KOTLIN_TOOLING_METADATA_SCHEMA_VERSION,
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
        // The Kotlin Toolchain always compiles the Java sources of the JVM fragment and has no equivalent of KGP's
        // legacy 'withJava()' opt-in. Reporting false is therefore correct despite Java sources being compiled:
        // 'withJavaEnabled' describes that Gradle wiring, not the presence of Java sources,
        // See JvmExtras documentation for more details.
        extras = jvmRelease()?.let { TargetExtras(jvm = JvmExtras(jvmTarget = it, withJavaEnabled = false)) },
    )
    platform == Platform.ANDROID -> ProjectTarget(
        target = KGP_ANDROID_TARGET,
        platformType = "androidJvm",
        // Both fields are Java versions, not Android SDK levels (see their documentation). AGP configures the Java
        // language level and the bytecode version separately, while the JVM release of the fragment sets both at
        // once, so we report it in both fields.
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
 *
 * The release defaults to the JDK version, so it is only null if it was explicitly set to null. The Kotlin and Java
 * compiler defaults then apply, and they differ, so there is no single version to report as the compatibility of the
 * target: this is why the targets report no extras at all in that case.
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