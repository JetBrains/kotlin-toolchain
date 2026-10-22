/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import kotlinx.serialization.Serializable
import org.apache.maven.artifact.versioning.ComparableVersion

/**
 * The expected pattern for the Kotlin compiler version setting.
 * It's used in diagnostics and to extract the default language version from the compiler version string.
 */
val KotlinCompilerVersionPattern = Regex("""(?<languageVersion>\d+\.\d+)\..*""")

@Serializable
@JvmInline
value class KotlinLanguageVersion(
    /**
     * The string notation of this version, in the form "major.minor".
     *
     * Examples: 2.0, 2.1, 2.2, 2.3, 2.4
     */
    val notation: String
) : Comparable<KotlinLanguageVersion> {

    override fun compareTo(other: KotlinLanguageVersion): Int {
        val [major, minor] = notation.split(".")
        val [otherMajor, otherMinor] = other.notation.split(".")
        val majorResult = compareValues(major.toInt(), otherMajor.toInt())
        if (majorResult != 0) return majorResult
        return compareValues(minor.toInt(), otherMinor.toInt())
    }

    companion object {
        val Kotlin20 = KotlinLanguageVersion("2.0")
    }
}

/**
 * Represents a version of the Kotlin compiler and standard library.
 * It's different from [KotlinLanguageVersion], which is purely the `major.minor` language version.
 */
@Serializable
@JvmInline
value class KotlinVersion(
    /**
     * The string notation of this compiler version.
     *
     * Examples: 2.0.0, 2.1.20-RC1
     */
    val notation: String
) : Comparable<KotlinVersion> {

    /**
     * The language version that corresponds to this Kotlin compiler version.
     */
    val languageVersion: KotlinLanguageVersion
        get() = run {
            val match = KotlinCompilerVersionPattern.matchEntire(notation)
                ?: error("Invalid Kotlin compiler version '$notation'") // already checked in the frontend
            val languageVersionGroup = match.groups["languageVersion"]
                ?: error("The 'languageVersion' capturing group should be present and mandatory in the Kotlin version regex," +
                        "but got: ${KotlinCompilerVersionPattern.pattern}")
            return KotlinLanguageVersion(languageVersionGroup.value)
        }

    override fun compareTo(other: KotlinVersion): Int =
        ComparableVersion(notation).compareTo(ComparableVersion(other.notation))

    /**
     * Whether this version supports the dedicated `KotlinWasmCompiler` class for WebAssembly compilation.
     */
    fun hasSeparateWasmCompiler(): Boolean = this >= firstWithSeparateWasmCompiler

    /**
     * Whether this version has proper incremental compilation support in BTA (including tracking of all compiler
     * options).
     */
    fun supportsIncrementalCompilationInBTA(): Boolean = this >= firstWithCorrectIncrementalCompilationInBTA

    /**
     * Whether this version has BTA support for `CompilerMessageRenderer`.
     */
    fun supportsCompilerMessageRendererInBTA(): Boolean = this >= firstWithBtaCompilerMessageRenderer

    /**
     * Whether this version requires to place the `org.jetbrains.kotlin:kotlin-build-tools-compat` on the BTA classpath
     * in addition to `kotlin-build-tools-impl`.
     */
    fun requiresBuildToolsApiCompatibilityModule(): Boolean = this < minBtaImplWithoutCompatibilityModule

    /**
     * Whether the `-Xir-produce-klib-file` option is set by default for Wasm and JS compilations in this version.
     * This also means it produces a warning if specified explicitly, so we must omit it when it's the default.
     */
    fun compilesWebToKlibByDefault(): Boolean = this >= firstWithPackedKlibByDefaultForWeb

    /**
     * Whether this compiler version contains [sun.misc.Unsafe] usages, and thus requires suppressing the warning.
     */
    fun hasSunMiscUnsafeUsages(): Boolean = this < firstWithoutUnsafeUsages

    /**
     * Whether this compiler version supports building Kotlin/Native caches for external dependencies without crashing.
     */
    fun canCacheNativeDependencies(): Boolean = this >= firstSupportingNativeDependenciesCaching

    /**
     * Whether the Linux cache builder for Kotlin/Native caches supports whitespace in paths in this version
     * (fixed in KT-86824).
     */
    fun supportsSpaceInNativeCacheOnLinux(): Boolean = this >= firstSupportingSpacesOnLinux

    /**
     * Whether the JVM compiler of this version sanitizes forbidden file name characters when deriving the
     * `META-INF/<module-name>.kotlin_module` entry name from `-module-name` (KT-82216), which makes group-qualified
     * JVM module names (`group:name`) safe to use.
     */
    fun supportsGroupQualifiedJvmModuleName(): Boolean = this >= firstWithSanitizedJvmModuleFileName

    companion object {
        private val firstWithSanitizedJvmModuleFileName = KotlinVersion("2.4.0-Beta2") // See KT-82216
        private val minBtaImplWithoutCompatibilityModule = KotlinVersion("2.3.0")
        private val firstWithBtaCompilerMessageRenderer = KotlinVersion("2.4.0-Beta2")
        private val firstWithoutUnsafeUsages = KotlinVersion("2.4.0")
        private val firstWithCorrectIncrementalCompilationInBTA = KotlinVersion("2.4.0")
        private val firstWithSeparateWasmCompiler = KotlinVersion("2.4.0")
        // TODO the fix of KT-86824 is tagged for a cherry-pick, so an earlier 2.4.x version may support it as well
        private val firstSupportingSpacesOnLinux = KotlinVersion("2.4.20-Beta2") // See KT-86824
        private val firstSupportingNativeDependenciesCaching = KotlinVersion("2.4.20-RC2") // See KT-88316
        private val firstWithPackedKlibByDefaultForWeb = KotlinVersion("2.4.20")
    }
}
