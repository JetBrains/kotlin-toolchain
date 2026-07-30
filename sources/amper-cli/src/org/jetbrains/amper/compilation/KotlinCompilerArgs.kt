/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compilation

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.cli.context.AmperProjectTempRoot
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.engine.BuildTask
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.FragmentDependencyType
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.flow.toPlatform
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.frontend.schema.kotlin.KotlinVersion
import org.jetbrains.amper.tasks.SourceRoot
import org.jetbrains.amper.tasks.ios.IosConventions
import org.jetbrains.amper.tasks.wasm.WasmTarget
import org.jetbrains.amper.util.BuildType
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
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
    val languageVersion = kotlinUserSettings.languageVersion

    if (isMultiplatform) {
        add("-Xmulti-platform")

        // When languageVersion == null, we don't pass anything to the compiler, so it uses its own default.
        // This case is similar to languageVersion >= 2.0 because we forbid compiler versions < 2.0.0 in the frontend.
        if (!isMetadataCompilation
            && (languageVersion == null || languageVersion >= KotlinVersion.Kotlin20)
        ) {
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

    if (languageVersion != null) {
        add("-language-version=${languageVersion.schemaValue}")
    }
    kotlinUserSettings.apiVersion?.let { apiVersion ->
        add("-api-version=${apiVersion.schemaValue}")
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

    // FIXME remove in Kotlin 2.2.20 (this is needed only in 2.2.0, not before, not after)
    val javaSourceRoots = (fragments.flatMap { it.sourceRoots } + additionalSourceRoots.map { it.path })
        .filter { it.exists() && it.listDirectoryEntries().isNotEmpty() }
    javaSourceRoots.forEach {
        add("-Xjava-source-roots=${it.pathString}")
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

enum class KotlinCompilationType(val argName: String) {
    LIBRARY("library"),
    BINARY("program"),
    IOS_FRAMEWORK("framework");

    fun outputFilename(module: AmperModule, platform: Platform, isTest: Boolean): String = when {
        this == LIBRARY -> "${moduleName(module, isTest)}.klib"
        this == IOS_FRAMEWORK -> IosConventions.KOTLIN_FRAMEWORK_NAME  // TODO: Allow customization
        this == BINARY && platform.isDescendantOf(Platform.MINGW) -> "${moduleName(module, isTest)}.exe"
        else -> "${moduleName(module, isTest)}.kexe"
    }

    fun moduleName(module: AmperModule, isTest: Boolean): String = when(this) {
        IOS_FRAMEWORK -> if (isTest) module.nameWithoutDashes + "Test" else module.nameWithoutDashes
        else -> module.kotlinModuleName(isTest)
    }

    private val AmperModule.nameWithoutDashes get() = userReadableName.replace("-", "").replace("_", "")
}

// TODO should we make it unique by using the full path?
internal fun AmperModule.kotlinModuleName(isTest: Boolean) =
    if (isTest) userReadableName + "_test" else userReadableName

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
): List<String> = buildList {
    if (kotlinUserSettings.debug ?: (buildType == BuildType.Debug)) {
        add("-g")
    }
    if (kotlinUserSettings.optimization ?: (buildType == BuildType.Release)) {
        add("-opt")
    }
    kotlinUserSettings.linkerOptions.forEach { opt ->
        add("-linker-option=$opt")
    }

    add("-ea")
    add("-produce=${compilationType.argName}")

    // todo (AB) : [AMPER-721] This is replicated from KGP logic, though it seems to be some outdated stuff
    //  https://jetbrains.team/p/kt/repositories/kotlin/revision/686d00ddf54aa082ed98ad87747befa04ed9168f?file=libraries%2Ftools%2Fkotlin-gradle-plugin%2Fsrc%2Fmain%2Fkotlin%2Forg%2Fjetbrains%2Fkotlin%2Fgradle%2Ftargets%2Fnative%2FKotlinNativeCompilation.kt&from-line=NEW%3A105
    add("-target=${fragmentPlatforms.first().nameForCompiler}")

    if (refinesPaths.isNotEmpty()) {
        add("-Xrefines-paths=${refinesPaths.joinToString(",")}")
    }

    if (friendPaths.isNotEmpty()) {
        add("-friend-modules=${friendPaths.joinToString(File.pathSeparator)}")
    }

    // TODO full module path including entire hierarchy? -Xshort-module-name)
    val isMetadataCompilation = fragmentPlatforms.size > 1 && fragments.size == 1
    if (isMetadataCompilation) {
        val moduleName = "${compilationType.moduleName(task.module, task.isTest)}_${fragments.single().name}"
        add("-module-name=$moduleName")
        add("-Xshort-module-name=$moduleName")

        add("-Xmetadata-klib")

        // The following flags are ported from the KGP plugin.
        add("-no-default-libs")
        add("-no-endorsed-libs")
        add("-nopack")
        add("-nostdlib")
        add("-Xexport-kdoc")
    } else {
        add("-module-name=${compilationType.moduleName(task.module, task.isTest)}")
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

    // todo (AB) : [AMPER-721] Remove this logging after AMPER-721 is done
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
): List<String> = buildList {
    add("-Xwasm")
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
): List<String> = buildList {
    add("-Xir-per-file")
    add("-Xir-minimized-member-names")
    add("-Xir-generate-inline-anonymous-functions")
    add("-Xgenerate-polyfills=false")
    add("-Xgenerate-dts")
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
): List<String> = buildList {

    if (friendPaths.isNotEmpty()) {
        add("-Xfriend-modules=${friendPaths.joinToString(File.pathSeparator)}")
    }

    // Common args last, because they contain free compiler args
    addAll(
        kotlinCommonCompilerArgs(
            isMultiplatform = true,
            kotlinUserSettings = kotlinUserSettings,
            fragments = fragments,
            additionalSourceRoots = additionalSourceRoots,
            compilerPlugins = compilerPlugins,
        )
    )

    add("-ir-output-name")
    add(moduleName)

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
        add("-Xir-produce-klib-file")
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
    // TODO full module path including entire hierarchy? -Xshort-module-name)
    add("-module-name=$moduleName")
    if (classpath.isNotEmpty()) {
        add("-classpath=${classpath.joinToString(File.pathSeparator)}")
    }

    add("-Xmetadata-klib")

    val kotlinVersion = ComparableVersion(kotlinUserSettings.compilerVersion)
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

    // todo (AB) : [AMPER-721] Remove this logging after AMPER-721 is done
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

    val since: ComparableVersion = ComparableVersion("2.3.20-Beta1")
}

/**
 * All platforms supported by the compiler argument '-Xtarget-platform'
 */
private fun Platform.toXTargetPlatform(kotlinVersion: ComparableVersion): XTargetPlatform? =
    when {
        this == Platform.JVM  -> XTargetPlatform.JVM
        this == Platform.ANDROID -> XTargetPlatform.JVM
        this == Platform.JS -> XTargetPlatform.JS
        this == Platform.WASM_JS -> XTargetPlatform.WasmJs
        this == Platform.WASM_WASI -> XTargetPlatform.WasmWasi
        this.isDescendantOf(Platform.NATIVE)-> XTargetPlatform.Native
        else -> null
    }?.takeIf { it.since <= kotlinVersion }