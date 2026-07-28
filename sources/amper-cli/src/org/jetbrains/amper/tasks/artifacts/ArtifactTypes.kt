/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.artifacts

import org.jetbrains.amper.cli.context.AmperBuildOutputRoot
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.commonizedCinteropLibrariesRoot
import org.jetbrains.amper.stdlib.io.path.listDirectoryEntriesIfExistsOrEmpty
import org.jetbrains.amper.tasks.ProjectTasksBuilder.Companion.testSuffix
import org.jetbrains.amper.tasks.artifacts.api.Artifact
import java.io.Serializable
import java.nio.file.Path
import kotlin.io.path.div

/**
 * A base class for all artifact implementations.
 * Serializable is an implementation detail for the cacheability mechanism.
 */
abstract class ArtifactBase(
    buildOutputRoot: AmperBuildOutputRoot,
) : Artifact, Serializable {
    /**
     * Components that uniquely identify the artifact among the others of the same type.
     * These are used to automatically generate the artifact's path.
     */
    protected abstract fun idComponents() : List<String>

    /**
     * An optional explicitly specified path that should be used instead of auto-generated one.
     * If this is specified, [idComponents] are not used.
     */
    protected open val conventionPath: Path? get() = null

    override val path: Path by lazy {
        conventionPath ?: idComponents().joinToString(separator = "")
            .let { buildOutputRoot.path / "artifacts" / javaClass.simpleName / it }
    }
}

/**
 * An artifact is associated with a fragment
 */
abstract class FragmentScopedArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    val fragment: Fragment,
) : ArtifactBase(buildOutputRoot) {
    val module get() = fragment.module
    val fragmentName get() = fragment.name
    val isTest get() = fragment.isTest
    val platforms get() = fragment.platforms

    override fun idComponents() = listOf(module.userReadableName, fragmentName)
}

/**
 * An artifact is associated with a [module].
 */
abstract class ModuleScopedArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    val module: AmperModule,
) : ArtifactBase(buildOutputRoot) {
    override fun idComponents() = [module.userReadableName]
}

/**
 * An artifact is associated with a module, leaf platform, and test modifier.
 * Basically, it is bound to the compilation for the specified leaf fragment.
 */
abstract class CompilationScopedArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    val module: AmperModule,
    val platform: Platform,
    val isTest: Boolean,
) : ArtifactBase(buildOutputRoot) {
    val moduleName get() = module.userReadableName

    init {
        require(platform.isLeaf) { "Only leaf platforms are expected here, got $platform" }
    }

    override fun idComponents() = listOf(module.userReadableName, platform.pretty, isTest.testSuffix)
}

/**
 * Kotlin + Java source directory tree.
 */
open class KotlinJavaSourceDirArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    fragment: Fragment,
    override val conventionPath: Path? = null,
) : FragmentScopedArtifact(buildOutputRoot, fragment)

/**
 * JVM resources directory tree.
 */
open class JvmResourcesDirArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    fragment: Fragment,
    override val conventionPath: Path? = null,
) : FragmentScopedArtifact(buildOutputRoot, fragment)

/**
 * Cinterop .def file.
 */
open class CinteropDefFileArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    fragment: Fragment,
    override val conventionPath: Path? = null,
) : FragmentScopedArtifact(buildOutputRoot, fragment)

/**
 * All the commonized cinterop libraries for the [module].
 * The layout is dictated by the commonizer tool:
 * ```
 * "dir"/
 *   - "(platform1; platform2)"
 *     - "foo"/ **
 *     - "bar"/ **
 *   - "(platform1; platform2; platform3)"
 *     - "foo"/ **
 *   - "(platform4; platform5)"
 *     - "baz"/ **
 * ```
 * Use [org.jetbrains.amper.kotlin.native.asCommonizerTarget] to get subdirectory names for the platform sets.
 */
open class CinteropCommonizedKlibArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    module: AmperModule,
) : ModuleScopedArtifact(buildOutputRoot, module) {
    override val path: Path = module.commonizedCinteropLibrariesRoot(buildOutputRoot.path)
}

/**
 * A directory that contains compiled cinterop `.klib` files.
 * The directory may be empty/non-existent.
 */
open class CinteropKlibsArtifact(
    buildOutputRoot: AmperBuildOutputRoot,
    module: AmperModule,
    platform: Platform,
    override val path: Path,
) : CompilationScopedArtifact(buildOutputRoot, module, platform, false) {
    init {
        require(platform.isLeaf) { "Only leaf platforms are expected here, got $platform" }
    }

    fun allKlibs() = path.listDirectoryEntriesIfExistsOrEmpty("*.klib")
}
