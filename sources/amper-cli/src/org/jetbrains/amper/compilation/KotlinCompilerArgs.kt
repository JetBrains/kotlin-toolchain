/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.compilation.compiler.KotlinCompiler
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.flow.toPlatform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.schema.KotlinVersion
import org.jetbrains.amper.frontend.schema.KotlinLanguageVersion
import org.jetbrains.amper.frontend.schema.effectiveArtifactId
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.tasks.SourceRoot
import org.jetbrains.amper.tasks.ios.IosConventions
import org.jetbrains.amper.tasks.metadata.sourceSetName
import org.jetbrains.amper.tasks.wasm.WasmTarget
import org.jetbrains.amper.util.BuildType
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.extension
import kotlin.io.path.pathString
import kotlin.io.path.walk
import kotlin.io.path.writeText

internal val validSourceFileExtensions = setOf("kt", "java")

private val logger = org.slf4j.LoggerFactory.getLogger("compilation/KotlinCompilerArgs")

private fun kotlinCommonCompilerArgs(
    isMultiplatform: Boolean,
    kotlinUserSettings: KotlinUserSettings,
    fragments: List<Fragment>,
    additionalSourceRoots: List<SourceRoot>,
    compilerPlugins: List<ResolvedCompilerPlugin>,
    isMetadataCompilation: Boolean = false,
): List<String> = buildList {
    if (isMultiplatform) {
        add("-Xmulti-platform")

        if (!isMetadataCompilation && kotlinUserSettings.effectiveLanguageVersion >= KotlinLanguageVersion.Kotlin20) {
            val additionalSourceRootsByFragmentName = additionalSourceRoots.groupBy(
                keySelector = { it.fragmentName },
                valueTransform = { it.path },
            )
            fragments.forEach { fragment ->
                add("-Xfragments=${fragment.name}")

                fragment.fragmentDependencies.filter { it.type == FragmentDependencyType.REFINE }.forEach {
                    add("-Xfragment-refines=${fragment.name}:${it.target.name}")
                }

                // for generated sources that must be associated with existing fragments
                val additionalFragmentSrcRoots = additionalSourceRootsByFragmentName[fragment.name] ?: emptyList()
                fragment.allSourceFiles(additionalFragmentSrcRoots).forEach { sourceFile ->
                    add("-Xfragment-sources=${fragment.name}:$sourceFile")
                }
            }
        }
    }

    if (kotlinUserSettings.languageVersion != null) {
        add("-language-version=${kotlinUserSettings.languageVersion.notation}")
    }
    if (kotlinUserSettings.apiVersion != null) {
        add("-api-version=${kotlinUserSettings.apiVersion.notation}")
    }

    if (kotlinUserSettings.allWarningsAsErrors) {
        add("-Werror")
    }
    if (kotlinUserSettings.suppressWarnings) {
        add("-nowarn")
    }
    if (kotlinUserSettings.verbose) {
        add("-verbose")
    }
    if (kotlinUserSettings.progressiveMode) {
        add("-progressive")
    }
    kotlinUserSettings.explicitApi.compilerOptionValue?.let { explicitApi ->
        add("-Xexplicit-api=$explicitApi")
    }
    kotlinUserSettings.optIns.forEach {
        add("-opt-in=$it")
    }
    kotlinUserSettings.languageFeatures.forEach {
        add("-XXLanguage:+$it")
    }
    // Switch to -Xcompiler-plugin option when it's ready (currently a prototype, and K2-only)
    // https://jetbrains.slack.com/archives/C942U8L4R/p1708709995859629
    compilerPlugins.forEach { plugin ->
        // Note: this is technically wrong, because we resolve each compiler plugin classpath independently, but the
        // kotlin compiler loads all plugin classpaths together in a single classloader (so we don't have cross-plugin
        // conflict resolution). At the moment, all compiler plugins have a single jar, so this is not a problem.
        // The proper way would be to resolve all plugins in a single resolution scope to resolve conflicts.
        // However, the new -Xcompiler-plugin argument will actually work with one classpath per plugin, loaded in
        // independent classloaders, so we would have to change it back when doing the switch, so let's wait instead.
        plugin.classpath.forEach { path ->
            add("-Xplugin=${path.absolutePathString()}")
        }
        plugin.options.forEach { opt ->
            add("-P")
            add("plugin:${plugin.id}:${opt.name}=${opt.value}")
        }
    }
    kotlinUserSettings.freeCompilerArgs.forEach {
        add(it)
    }
}

private fun Fragment.allSourceFiles(additionalSourceRoots: List<Path>): List<Path> =
    (sourceRoots + additionalSourceRoots).flatMap { srcRoot ->
        // In Kotlin >= 2.2, we need to list all source files (not just dirs).
        // Also, we don't want Swift or plist files here
        srcRoot.walk().filter { it.extension in validSourceFileExtensions }
    }

internal fun kotlinJvmCompilerArgs(
    isMultiplatform: Boolean,
    userSettings: CompilationUserSettings,
    classpath: List<Path>,
    compilerPlugins: List<ResolvedCompilerPlugin>,
    jdkHome: Path,
    friendPaths: List<Path>,
    fragments: List<Fragment>,
    additionalSourceRoots: List<SourceRoot>,
    moduleName: String,
): List<String> = buildList {
    if (userSettings.jvmRelease != null) {
        add("-Xjdk-release=${userSettings.jvmRelease.releaseNumber}")
    }
    add("-jdk-home=${jdkHome.pathString}")
    add("-classpath=${classpath.joinToString(File.pathSeparator)}")
    add("-no-stdlib") // that is specifically for the JVM
    add("-module-name=$moduleName")

    if (friendPaths.isNotEmpty()) {
        // KT-34277 Kotlinc processes -Xfriend-paths differently for Javascript vs. JVM, using different list separators
        // https://github.com/JetBrains/kotlin/blob/4964ee12a994bc846ecdb4810486aaf659be00db/compiler/cli/cli-common/src/org/jetbrains/kotlin/cli/common/arguments/K2JVMCompilerArguments.kt#L531
        add("-Xfriend-paths=${friendPaths.joinToString(",")}")
    }

    if (userSettings.kotlin.storeJavaParameterNames) {
        add("-java-parameters")
    }
    // Common args last, because they contain free compiler args
    addAll(kotlinCommonCompilerArgs(
        isMultiplatform = isMultiplatform,
        kotlinUserSettings = userSettings.kotlin,
        fragments = fragments,
        additionalSourceRoots = additionalSourceRoots,
        compilerPlugins = compilerPlugins,
    ))
}

/**
 * The kind of artifact produced by a compilation, modeled after the Kotlin/Native compiler's `-produce` option
 * ([argName] is the value passed to that option). For web (JS/Wasm) compilations, it maps to the `-Xir-produce-*`
 * flags instead: a klib for [LIBRARY], an executable for [BINARY].
 */
enum class KotlinCompilationType(val argName: String) {
    LIBRARY("library"),
    BINARY("program"),
    IOS_FRAMEWORK("framework");

    /**
     * The name of the file produced when compiling the given [module] as this [KotlinCompilationType]
     * (e.g. `lib.klib` for [LIBRARY], `lib.kexe` for [BINARY]).
     *
     * The file is named after the short [moduleName]: for [LIBRARY], the same value is also passed as
     * `-Xshort-module-name` (native) or `-ir-output-name` (web), so a klib's `short_name` and its file name are
     * single-sourced and always agree (corresponds to KGP's `baseName`).
     */
    fun outputFilename(module: AmperModule, platform: Platform, isTest: Boolean): String = when (this) {
        LIBRARY -> "${moduleName(module, isTest)}.klib"
        IOS_FRAMEWORK -> IosConventions.KOTLIN_FRAMEWORK_NAME  // TODO: Allow customization
        BINARY -> {
            val extension = if (platform.isDescendantOf(Platform.MINGW)) "exe" else "kexe"
            "${moduleName(module, isTest)}.$extension"
        }
    }

    /**
     * The short (bare) module name to use when compiling the given [module] as this [KotlinCompilationType]
     * (with a `_test`/`Test` suffix for the test compilation).
     *
     * For [LIBRARY] and [BINARY], this is the equivalent of KGP's `baseName` (the Gradle project name): the single
     * source for both the output file names ([outputFilename], `-ir-output-name`) and the klib `short_name`
     * (`-Xshort-module-name`), so a klib's `short_name` always agrees with its default file name.
     * Unlike [klibUniqueName] (which owns the klib identity), this name must stay file-system safe, because it is
     * used in file names.
     *
     * [IOS_FRAMEWORK] is special: the framework name feeds the generated Objective-C/Swift header, so it is
     * additionally stripped of characters invalid in Objective-C identifiers.
     */
    fun moduleName(module: AmperModule, isTest: Boolean): String = when(this) {
        IOS_FRAMEWORK -> if (isTest) module.nameWithoutDashes + "Test" else module.nameWithoutDashes
        else -> if (isTest) module.userReadableName + "_test" else module.userReadableName
    }

    private val AmperModule.nameWithoutDashes get() = userReadableName.replace("-", "").replace("_", "")
}

/**
 * The name that identifies the klibs produced for this module (with a `_test` suffix for the test klibs).
 *
 * This ends up as `unique_name` in the klib manifests, and consumers record it in the `depends` field of their own
 * manifests. It therefore follows the KGP convention `group:artifactId`, so that a library keeps its klib identity
 * when moving between Gradle and the Kotlin Toolchain (KTC-6014). The group is omitted when the module has no
 * publishing group configured, just like KGP omits an empty Gradle project group.
 *
 * This name must not be used for file names: the `:` separator is not a valid file name character on Windows.
 */
internal fun AmperModule.klibUniqueName(isTest: Boolean): String {
    val artifactId = publishingSettings.effectiveArtifactId(this)
    val baseName = if (isTest) "${artifactId}_test" else artifactId
    val group = publishingSettings.group?.takeIf { it.isNotBlank() }
    val klibUniqueName = if (group == null) baseName else "$group:$baseName"
    return klibUniqueName.replace(forbiddenModuleNameChars, "_")
}

/**
 * The JVM `-module-name` to use when compiling this module (with a `_test` suffix for the test compilation).
 * It is used for both the `META-INF/<module-name>.kotlin_module` entry name and the `internal` ABI.
 *
 * Since Kotlin 2.4, it is possible to use qualified module name,
 * because compiler started sanitizing forbidden file name characters when deriving the `META-INF/<module-name>.kotlin_module`
 * entry name (KT-82216).
 */
internal fun AmperModule.jvmModuleName(kotlinVersion: KotlinVersion, isTest: Boolean): String =
    if (kotlinVersion.supportsGroupQualifiedJvmModuleName()) klibUniqueName(isTest)
    else KotlinCompilationType.LIBRARY.moduleName(this, isTest)

/**
 * The name that identifies the metadata klib produced for the given shared [fragment] of this module
 * (e.g. `org.jetbrains.kotlinx:atomicfu_commonMain`).
 *
 * Each shared fragment is compiled into its own metadata klib, and several of them can end up on the same consumer
 * classpath, so the source set name is appended to the module's [klibUniqueName] to keep each klib's name unique
 * (same as KGP).
 */
internal fun AmperModule.metadataKlibUniqueName(fragment: Fragment, isTest: Boolean): String =
    "${klibUniqueName(isTest)}_${fragment.sourceSetName()}"

/**
 * Characters that are not allowed in module names, replaced with `_` (matches KGP's `filterModuleName` sanitization).
 */
private val forbiddenModuleNameChars = Regex("""[\\/\r\n\t]""")

/**
 * Chooses the value of the Kotlin/Native compiler's `-target` option for a compilation covering [fragmentPlatforms].
 *
 * Leaf compilations cover a single platform, so there is nothing to choose there. Shared native (metadata)
 * compilations cover several platforms at once, yet konanc still insists on a single `-target`. That target doesn't
 * end up in the resulting metadata klib (see the `-manifest` handling in [kotlinNativeCompilerArgs]), but it does
 * decide which platform libraries konanc looks for, so it has to be one that this host can actually compile for.
 *
 * This mirrors what KGP does for `KotlinSharedNativeCompilation`:
 * `konanTargets.find { it.enabledOnCurrentHostForKlibCompilation(...) } ?: konanTargets.first()`.
 * Unlike KGP we can't rely on the declaration order of the targets, because [fragmentPlatforms] is an unordered set,
 * so the fallback picks the first platform in [Platform] declaration order to stay deterministic across runs.
 */
internal fun selectKotlinNativeCompilerTarget(
    fragmentPlatforms: Set<Platform>,
    system: SystemInfo = SystemInfo.CurrentHost,
): Platform {
    val candidates = fragmentPlatforms.sorted()
    return candidates.firstOrNull { it.supportsKlibCompilationFrom(system) } ?: candidates.first()
}

/**
 * Whether klibs for this leaf [Platform] can be compiled from a host with the given [system].
 *
 * Apple targets need the toolchain that only ships with Xcode, so they require a macOS host. All other native targets
 * can be cross-compiled from any supported host.
 * This is the equivalent of the Kotlin/Native `HostManager.isEnabled` check for Kotlin Toolchain's [Platform]s.
 */
private fun Platform.supportsKlibCompilationFrom(system: SystemInfo): Boolean =
    !isDescendantOf(Platform.APPLE) || system.family.isMac

/**
 * Whether the Kotlin/Native compiler should apply global optimizations for the given [buildType].
 */
internal fun KotlinUserSettings.optimizationEnabled(buildType: BuildType): Boolean =
    optimization ?: (buildType == BuildType.Release)

context(task: BuildTask)
internal fun kotlinNativeCompilerArgs(
    buildType: BuildType,
    kotlinUserSettings: KotlinUserSettings,
    compilerPlugins: List<ResolvedCompilerPlugin>,
    entryPoint: String?,
    libraryPaths: List<Path>,
    exportedLibraryPaths: List<Path>,
    fragments: List<Fragment>,
    sourceFiles: List<Path>,
    additionalSourceRoots: List<SourceRoot>,
    binaryOptions: Map<String, String>,
    outputPath: Path,
    compilationType: KotlinCompilationType,
    include: Path?,
    fragmentPlatforms: Set<Platform>,
    friendPaths: List<Path> = [],
    refinesPaths: List<Path> = [],
    metadataManifestFile: Path? = null,
    otherLinkerOpts: List<String> = emptyList(),
    // Only link (second-stage) compilations can use caches, it should be null in all other cases.
    nativeCaches: NativeCompilerCaches? = null,
): List<String> = buildList {
    if (kotlinUserSettings.debug ?: (buildType == BuildType.Debug)) {
        add("-g")
    }
    if (kotlinUserSettings.optimizationEnabled(buildType)) {
        add("-opt")
    }
    (otherLinkerOpts + kotlinUserSettings.linkerOptions).forEach { opt ->
        add("-linker-option=$opt")
    }

    add("-ea")
    add("-produce=${compilationType.argName}")

    // This is replicated from KGP logic.
    // Parameter 'target' is needed for single-platform compilations only and is not applicable to the multiplatform compilation,
    // but it is left mandatory for that case as well:
    // https://jetbrains.team/p/kt/repositories/kotlin/revision/686d00ddf54aa082ed98ad87747befa04ed9168f?file=libraries%2Ftools%2Fkotlin-gradle-plugin%2Fsrc%2Fmain%2Fkotlin%2Forg%2Fjetbrains%2Fkotlin%2Fgradle%2Ftargets%2Fnative%2FKotlinNativeCompilation.kt&from-line=NEW%3A105
    add("-target=${selectKotlinNativeCompilerTarget(fragmentPlatforms).nameForCompiler}")

    if (refinesPaths.isNotEmpty()) {
        add("-Xrefines-paths=${refinesPaths.joinToString(",")}")
    }

    if (friendPaths.isNotEmpty()) {
        add("-friend-modules=${friendPaths.joinToString(File.pathSeparator)}")
    }

    // The short module name is the single source for both the output file name (see KotlinCompilationType.outputFilename) 
    // and '-Xshort-module-name', so a klib's 'short_name' always agrees with its default file name. 
    // This mirrors KGP, where 'baseName' feeds both the output file name and 'shortModuleName' of the compile task.
    val shortModuleName = compilationType.moduleName(task.module, task.isTest)

    val isMetadataCompilation = fragmentPlatforms.size > 1 && fragments.size == 1
    if (isMetadataCompilation) {
        // The klib 'unique_name' is qualified with the publishing group, while 'short_name' isn't
        // (e.g. 'org.jetbrains.kotlinx:atomicfu_nativeMain' / 'atomicfu_nativeMain').
        val fragment = fragments.single()
        add("-module-name=${task.module.metadataKlibUniqueName(fragment, task.isTest)}")
        add("-Xshort-module-name=${shortModuleName}_${fragment.sourceSetName()}")

        add("-Xmetadata-klib")

        // The '-target' above is stamped by konanc into the 'native_targets' property of the produced klib manifest.
        // That's wrong for a shared-native metadata klib: consumers whose target set differs reject it with
        // "KLIB resolver: ... The target doesn't match. Expected 'X', found [Y]".
        // Just like KGP, we overwrite 'native_targets' with an empty value via a manifest addend file.
        // See KT-64525 "Clean-up target-related fields in manifest of klibs".
        if (metadataManifestFile != null) {
            add("-manifest=${metadataManifestFile.pathString}")
        }

        // The following flags are ported from the KGP plugin.
        add("-no-default-libs")
        add("-no-endorsed-libs")
        add("-nopack")
        add("-nostdlib")
        add("-Xexport-kdoc")
    } else if (compilationType == KotlinCompilationType.LIBRARY) {
        // Just like KGP, the klib 'unique_name' is qualified with the publishing group, and the 'short_name' isn't.
        add("-module-name=${task.module.klibUniqueName(task.isTest)}")
        add("-Xshort-module-name=$shortModuleName")
    } else {
        // Binaries and frameworks keep the bare module name, like in KGP: they have no klib manifest to stamp, and
        // the compiler warns that -Xshort-module-name is only supported when producing a library.
        add("-module-name=$shortModuleName")
    }

    if (compilationType != KotlinCompilationType.LIBRARY) {
        if (task.isTest) {
            add("-generate-test-runner")
        } else {
            if (entryPoint != null) {
                add("-entry=$entryPoint")
            }
        }
    }

    if (compilationType == KotlinCompilationType.IOS_FRAMEWORK) {
        // We always link the framework statically
        add("-Xstatic-framework")
    }

    binaryOptions.forEach { (key, value) ->
        add("-Xbinary=$key=$value")
    }

    libraryPaths.forEach {
        add("-library=${it.pathString}")
    }

    exportedLibraryPaths.forEach {
        add("-Xexport-library=${it.pathString}")
    }

    // Before the common args (and therefore before the user's free compiler args) on purpose: the cache directory
    // options are single-valued, so a user explicitly passing one of them still wins.
    if (nativeCaches != null) {
        require(compilationType != KotlinCompilationType.LIBRARY) {
            "Compiler caches are only applicable to link compilations, but the compilation type is $compilationType"
        }
        addAll(nativeCaches.compilerArgs())
    }

    // Common args last, because they contain free compiler args
    addAll(kotlinCommonCompilerArgs(
        isMultiplatform = true,
        kotlinUserSettings = kotlinUserSettings,
        fragments = fragments,
        additionalSourceRoots = additionalSourceRoots,
        compilerPlugins = compilerPlugins,
        isMetadataCompilation = isMetadataCompilation,
    ))

    if (include != null) add("-Xinclude=${include.pathString}")

    // -output is after freeCompilerArgs because we don't allow overriding the output dir (it breaks task dependencies)
    // TODO forbid -output in freeCompilerArgs in the frontend, so it's clearer for the users
    add("-output=${outputPath.pathString}")

    // Skip adding sources to `IOS_FRAMEWORK`, since it will use `-Xinclude` argument instead.
    if (compilationType != KotlinCompilationType.IOS_FRAMEWORK) {
        addAll(sourceFiles.map { it.pathString })
    }

    // todo (AB) : [AMPER-719] Remove this logging after AMPER-719 is done
    logger.debug("Native metadata compilation args: \n${joinToString(System.lineSeparator()) { it }}")
}

internal fun kotlinWasmCompilerArgs(
    wasmTarget: WasmTarget,
    kotlinUserSettings: KotlinUserSettings,
    compilerPlugins: List<ResolvedCompilerPlugin>,
    libraryPaths: List<Path>,
    outputPath: Path,
    friendPaths: List<Path>,
    fragments: List<Fragment>,
    sourceFiles: List<Path>,
    additionalSourceRoots: List<SourceRoot>,
    moduleName: String,
    compilationType: KotlinCompilationType,
    buildType: BuildType?,
    include: Path?,
    cacheDirectory: Path? = null,
    klibUniqueName: String? = null,
): List<String> = buildList {
    if (!kotlinUserSettings.compilerVersion.hasSeparateWasmCompiler()) {
        add("-Xwasm")
    }
    add("-Xwasm-target=wasm-${wasmTarget.name.lowercase()}")

    addAll(
        kotlinWebCompilerArgs(
            kotlinUserSettings,
            compilerPlugins,
            libraryPaths,
            outputPath,
            friendPaths,
            fragments,
            sourceFiles,
            additionalSourceRoots,
            moduleName,
            compilationType,
            buildType,
            include,
            cacheDirectory,
            klibUniqueName,
        )
    )
}

internal fun kotlinJsCompilerArgs(
    kotlinUserSettings: KotlinUserSettings,
    compilerPlugins: List<ResolvedCompilerPlugin>,
    libraryPaths: List<Path>,
    outputPath: Path,
    friendPaths: List<Path>,
    fragments: List<Fragment>,
    sourceFiles: List<Path>,
    additionalSourceRoots: List<SourceRoot>,
    moduleName: String,
    compilationType: KotlinCompilationType,
    buildType: BuildType?,
    include: Path?,
    cacheDirectory: Path? = null,
    klibUniqueName: String? = null,
): List<String> = buildList {
    add("-Xir-per-file")
    add("-Xir-minimized-member-names")
    add("-Xir-generate-inline-anonymous-functions")
    add("-Xgenerate-polyfills=false")
    add("-Xgenerate-dts")
    // -Xes-long-as-bigint was introduced in 2.2.20, but we don't support lower Kotlin versions anyway, so this is fine
    add("-Xes-long-as-bigint")
    add("-target=es2015")
    add("-module-kind=es")

    addAll(
        kotlinWebCompilerArgs(
            kotlinUserSettings,
            compilerPlugins,
            libraryPaths,
            outputPath,
            friendPaths,
            fragments,
            sourceFiles,
            additionalSourceRoots,
            moduleName,
            compilationType,
            buildType,
            include,
            cacheDirectory,
            klibUniqueName,
        )
    )
}

private fun kotlinWebCompilerArgs(
    kotlinUserSettings: KotlinUserSettings,
    compilerPlugins: List<ResolvedCompilerPlugin>,
    libraryPaths: List<Path>,
    outputPath: Path,
    friendPaths: List<Path>,
    fragments: List<Fragment>,
    sourceFiles: List<Path>,
    additionalSourceRoots: List<SourceRoot>,
    moduleName: String,
    compilationType: KotlinCompilationType,
    buildType: BuildType?,
    include: Path?,
    cacheDirectory: Path?,
    klibUniqueName: String?,
): List<String> = buildList {

    if (friendPaths.isNotEmpty()) {
        add("-Xfriend-modules=${friendPaths.joinToString(File.pathSeparator)}")
    }

    // Note: unlike the other arg builders, the common args (and thus the user's free compiler args) are NOT last
    // here. Every flag added below is either positional, non-overridable on purpose (like -ir-output-dir), or
    // explicitly skipped when the user passes their own (like -Xir-module-name).
    addAll(
        kotlinCommonCompilerArgs(
            isMultiplatform = true,
            kotlinUserSettings = kotlinUserSettings,
            fragments = fragments,
            additionalSourceRoots = additionalSourceRoots,
            compilerPlugins = compilerPlugins,
        )
    )

    // It names the output file, so it stays short (file-system safe). The value is the short module name (see
    // KotlinCompilationType.moduleName), the same single source that WebCompileKlibTask uses to resolve the produced
    // klib file, so the two always agree (like KGP's 'baseName').
    add("-ir-output-name")
    add(moduleName)

    // The value from 'Xir-module-name' is stamped as 'unique_name' into the klib manifest,
    // qualified with the publishing group (see AmperModule.klibUniqueName).
    // Flag is not set if the user passes their own via free compiler args. The main reason is that even
    // if the flag had been set before applying 'freeCompilerArgs' (which is not true at the moment) so that
    // user defined value overrides it, the compiler would print a warning about the duplicated argument.
    if (klibUniqueName != null && kotlinUserSettings.freeCompilerArgs.none { it.startsWith("-Xir-module-name") }) {
        add("-Xir-module-name=$klibUniqueName")
    }

    if (buildType == BuildType.Release) {
        add("-Xir-dce")
    }

    // -d is after freeCompilerArgs because we don't allow overriding the output dir (it breaks task dependencies)
    // (specifying -d multiple times generates a warning, and only the last value is used)
    // TODO forbid -d in freeCompilerArgs in the frontend, so it's clearer for the users
    add("-ir-output-dir")
    add(outputPath.pathString)

    if (compilationType == KotlinCompilationType.BINARY) {
        add("-Xir-produce-js")
    } else {
        if (!kotlinUserSettings.compilerVersion.compilesWebToKlibByDefault()) {
            add("-Xir-produce-klib-file")
        }
    }

    cacheDirectory?.let { add("-Xcache-directory=${it.pathString}") }

    add("-libraries")
    add(libraryPaths.joinToString(File.pathSeparator) { it.pathString })

    if (include != null) add("-Xinclude=${include.pathString}")

    addAll(sourceFiles.map { it.pathString })
}

// https://github.com/JetBrains/kotlin/blob/v1.9.23/compiler/cli/cli-common/src/org/jetbrains/kotlin/cli/common/arguments/K2MetadataCompilerArguments.kt
internal fun kotlinMetadataCompilerArgs(
    kotlinUserSettings: KotlinUserSettings,
    moduleName: String,
    classpath: List<Path>,
    compilerPlugins: List<ResolvedCompilerPlugin>,
    outputPath: Path,
    friendPaths: List<Path>,
    refinesPaths: List<Path>,
    fragments: List<Fragment>,
    sourceFiles: List<Path>,
    additionalSourceRoots: List<SourceRoot>,
    fragmentPlatforms: Set<ResolutionPlatform>,
): List<String> = buildList {
    // The name is already qualified with the publishing group and suffixed with the source set name (see the call
    // site in MetadataCompileTask), matching KGP. There is no '-Xshort-module-name' counterpart here: that flag is
    // Kotlin/Native-only, so K2Metadata klibs carry no 'short_name' (same as KGP's common metadata klibs).
    add("-module-name=$moduleName")
    if (classpath.isNotEmpty()) {
        add("-classpath=${classpath.joinToString(File.pathSeparator)}")
    }

    add("-Xmetadata-klib")

    val kotlinVersion = kotlinUserSettings.compilerVersion
    val targetPlatforms = fragmentPlatforms.mapNotNull { it.toPlatform().toXTargetPlatform(kotlinVersion) }.toSet()
    if (targetPlatforms.isNotEmpty()) {
        add("-Xtarget-platform=${targetPlatforms.joinToString(",") { it.cliValue }}")
    }

    if (friendPaths.isNotEmpty()) {
        // KT-34277 Kotlinc processes -Xfriend-paths differently for Javascript vs. JVM, using different list separators
        // https://github.com/JetBrains/kotlin/blob/4964ee12a994bc846ecdb4810486aaf659be00db/compiler/cli/cli-common/src/org/jetbrains/kotlin/cli/common/arguments/K2JVMCompilerArguments.kt#L531
        add("-Xfriend-paths=${friendPaths.joinToString(",")}")
    }

    if (refinesPaths.isNotEmpty()) {
        add("-Xrefines-paths=${refinesPaths.joinToString(",")}")
    }

    // Common args last, because they contain free compiler args
    addAll(kotlinCommonCompilerArgs(
        isMultiplatform = true,
        kotlinUserSettings = kotlinUserSettings,
        fragments = fragments,
        additionalSourceRoots = additionalSourceRoots,
        compilerPlugins = compilerPlugins,
        isMetadataCompilation = true,
    ))

    // -d is after freeCompilerArgs because we don't allow overriding the output dir (it breaks task dependencies)
    // (specifying -d multiple times generates a warning, and only the last value is used)
    // TODO forbid -d in freeCompilerArgs in the frontend, so it's clearer for the users
    add("-d=${outputPath.pathString}")

    addAll(sourceFiles.map { it.pathString })

    // todo (AB) : [AMPER-719] Remove this logging after AMPER-719 is done
    logger.debug("Common metadata compilation args: \n${joinToString(System.lineSeparator()) { it }}")
}

inline fun <R> withKotlinCompilerArgFile(args: List<String>, tempRoot: AmperProjectTempRoot, block: (Path) -> R): R {
    // escaping rules from https://github.com/JetBrains/kotlin/blob/6161f44d91e235750077e1aaa5faff7047316190/compiler/cli/cli-common/src/org/jetbrains/kotlin/cli/common/arguments/preprocessCommandLineArguments.kt#L83
    val argString = args.joinToString(" ") { arg ->
        if (arg.contains(" ") || arg.contains("'")) {
            "'${arg.replace("\\", "\\\\").replace("'", "\\'")}'"
        } else {
            arg
        }
    }

    tempRoot.path.createDirectories()
    val argFile = createTempFile(tempRoot.path, "kotlin-args-", ".txt")
    return try {
        argFile.writeText(argString)
        block(argFile)
    } finally {
        argFile.deleteExisting()
    }
}

private enum class XTargetPlatform(val cliValue: String) {
    JVM("JVM"),
    JS("JS"),
    WasmJs("WasmJs"),
    WasmWasi("WasmWasi"),
    Native("Native");

    val since: KotlinVersion = KotlinVersion("2.3.20-Beta1")
}

/**
 * All platforms supported by the compiler argument '-Xtarget-platform'
 */
private fun Platform.toXTargetPlatform(kotlinVersion: KotlinVersion): XTargetPlatform? =
    when {
        this == Platform.JVM  -> XTargetPlatform.JVM
        this == Platform.ANDROID -> XTargetPlatform.JVM
        this == Platform.JS -> XTargetPlatform.JS
        this == Platform.WASM_JS -> XTargetPlatform.WasmJs
        this == Platform.WASM_WASI -> XTargetPlatform.WasmWasi
        this.isDescendantOf(Platform.NATIVE)-> XTargetPlatform.Native
        else -> null
    }?.takeIf { it.since <= kotlinVersion }