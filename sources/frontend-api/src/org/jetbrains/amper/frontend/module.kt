/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend

import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.amper.core.UsedInIdePlugin
import org.jetbrains.amper.frontend.plugins.AmperMavenPluginDescription
import org.jetbrains.amper.frontend.plugins.CheckFromPlugin
import org.jetbrains.amper.frontend.plugins.CustomCommandFromPlugin
import org.jetbrains.amper.frontend.plugins.TaskFromPluginDescription
import org.jetbrains.amper.frontend.schema.InternalSettings
import org.jetbrains.amper.frontend.schema.JdkSettings
import org.jetbrains.amper.frontend.schema.MavenPluginSettings
import org.jetbrains.amper.frontend.schema.Module
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.schema.PublishingSettings
import java.nio.file.Path
import kotlin.io.path.div

data class AmperModuleFileSource(val buildFile: Path) {
    /**
     * The directory containing the `module.yaml` or Gradle build file of the module.
     */
    val moduleDir: Path
        get() = buildFile.parent
}

sealed interface ModulePart<SelfT>

data class ModuleTasksPart(
    val settings: Map<String, TaskSettings>,
) : ModulePart<ModuleTasksPart> {
    data class TaskSettings(val dependsOn: List<String>)
}

enum class Layout {
    /**
     * Maven-like mode. Main and test sources are located inside src/ and sources are split by type (language, purpose,
     * etc.). The Gradle `java` plugin also uses this layout. It helps to simplify the transition between Kotlin and
     * Maven/Gradle builds.
     *
     * Example:
     *
     * src/
     *   main/
     *     java/
     *     kotlin/
     *     resources/
     *   test/
     *     java/
     *     kotlin/
     *     resources/
     */
    MAVEN_LIKE,

    /**
     * Mode, when `src` and `src@jvm` like platform
     * specific directories layout are used.
     * Non-Kotlin source sets have no directories at all.
     */
    AMPER,
}

/**
 * Just an aggregator for fragments and artifacts.
 */
// TODO Add trace.
interface AmperModule {
    /**
     * To reference module somehow in output.
     */
    val userReadableName: @NlsSafe String

    /**
     * An optional description for this module.
     * This description supports Markdown formatting and can span multiple lines.
     *
     * When writing multiline descriptions, the first line should act as a short summary that can stand on its own,
     * like commit messages. Only the first line is displayed by default in `./kotlin show modules`.
     *
     * This description is used by the CLI and by IDEs to show information about the module.
     * For libraries, it is also used as a description in published metadata by default.
     */
    val description: @NlsSafe String?

    val type: ProductType

    val source: AmperModuleFileSource

    /**
     * The platform aliases defined in this module.
     */
    val aliases: Map<@NlsSafe String, Set<Platform>>

    /**
     * List of all the fragments in the module. Can be empty if no platforms were specified.
     */
    val fragments: List<Fragment>

    val artifacts: List<Artifact>

    @Deprecated("Old mechanism. Use normal Kotlin API to expose things in the model")
    val parts: ClassBasedSet<ModulePart<*>>

    @UsedInIdePlugin
    val usedCatalog: VersionCatalog

    @UsedInIdePlugin
    val usedTemplates: List<VirtualFile>

    val mavenRepositories: List<RepositoryModel>

    val leafFragments get() = fragments.filterIsInstance<LeafFragment>()

    val leafPlatforms: Set<Platform> get() = leafFragments.map { it.platform }.toSet()

    val tasksFromPlugins: List<TaskFromPluginDescription>

    val checksFromPlugins: List<CheckFromPlugin>

    val customCommandsFromPlugins: List<CustomCommandFromPlugin>

    val layout: Layout

    val amperMavenPluginsDescriptions: List<AmperMavenPluginDescription>

    val mavenPluginSettings: MavenPluginSettings

    /**
     * [Module] instance for the "common" module tree.
     * Can be used to get traces for various global module properties.
     *
     * Be careful not to rely on anything non-context-agnostic through this value.
     * Use [fragments] to access context-specific [Module.settings] and [Module.dependencies].
     */
    val commonModuleNode: Module
}

/**
 * All the commonized cinterop libraries for the module.
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
 * Use the `asCommonizerTarget` API to get subdirectory names for the platform sets.
 */
fun AmperModule.commonizedCinteropLibrariesRoot(buildOutputRoot: Path): Path =
    buildOutputRoot / "commonized" / userReadableName

/**
 * Returns all fragments in this module that target at least the given set of [platforms].
 * If [isTest] is false, only production fragments are returned, otherwise - only test fragments are returned.
 *
 * When collecting dependencies, note that there is also [fragmentsToDependOnFromOtherModuleFragmentWith] with slightly
 * different semantics.
 */
fun AmperModule.fragmentsTargeting(platforms: Set<Platform>, isTest: Boolean = false): List<Fragment> {
    return fragments.filter { isTest == it.isTest && it.platforms.containsAll(platforms) }
}

/**
 * Returns all fragments in this module that target at least the given [platform].
 * If [isTest] is false, only production fragments are returned, otherwise - only test fragments are returned.
 *
 * When collecting dependencies, note that there is also [fragmentsToDependOnFromOtherModuleFragmentWith] with slightly
 * different semantics.
 */
fun AmperModule.fragmentsTargeting(platform: Platform, isTest: Boolean = false): List<Fragment> =
    fragmentsTargeting(setOf(platform), isTest)

/**
 * Returns all fragments in this module that should be consumed when __depending__ on it from a fragment 
 * from a __different module__ with the given [platforms].
 *
 * This might look similar to [fragmentsTargeting] but has few differences:
 * - Only non-test fragments are resolved as we don't allow depending on test fragments in any way
 *   (for test fixtures see AMPER-5066).
 * - It has a fallback for Android scenario to JVM, when [this module][this] doesn't have support for it.
 *   This aligns with how the dependency resolution works for Maven dependencies.
 */
fun AmperModule.fragmentsToDependOnFromOtherModuleFragmentWith(platforms: Set<Platform>): List<Fragment> {
    val alignedPlatforms = if (
        Platform.ANDROID in platforms && Platform.ANDROID !in leafPlatforms && Platform.JVM in leafPlatforms
    ) {
        // If we want to find fragments targeting Android, but the module doesn't support it, then (and only then) we can fall back to JVM
        platforms - Platform.ANDROID + Platform.JVM
    } else {
        platforms
    }

    return fragments.filter { !it.isTest && it.platforms.containsAll(alignedPlatforms) }
}

/**
 * Returns the publishing settings for this module (which are platform-agnostic).
 */
// We don't have to go through all fragments because these settings are platform-agnostic.
// We also don't care about test fragments because publication is inherently about main.
val AmperModule.publishingSettings: PublishingSettings
    get() = fragments.first { !it.isTest }.settings.publishing

/**
 * Returns whether maven publishing is enabled for this module.
 */
// We don't have to go through all fragments because this setting is platform-agnostic
fun AmperModule.isPublishingEnabled() = publishingSettings.enabled

/**
 * Returns whether JARs with sources for each platform should be published (as extra artifacts).
 */
// We don't have to go through all fragments, settings.publishing.publishSources is platform-agnostic.
fun AmperModule.shouldPublishSourcesJars() = publishingSettings.publishSources

/**
 * Returns whether published artifacts should be signed, and their signatures published alongside them.
 */
// We don't have to go through all fragments because this setting is platform-agnostic
fun AmperModule.isArtifactSigningEnabled() = publishingSettings.signArtifacts

/**
 * Returns the JDK settings for this module's production code.
 */
// We don't have to go through all fragments, the JdkSettings are platform-agnostic.
val AmperModule.jdkSettings: JdkSettings get() = fragments.first { !it.isTest }.settings.jvm.jdk

/**
 * Returns the JDK settings for this module's test code.
 */
// We don't have to go through all fragments, the JdkSettings are platform-agnostic.
val AmperModule.testJdkSettings: JdkSettings get() = fragments.first { it.isTest }.settings.jvm.jdk

/**
 * Returns the internal settings for this module's production code.
 */
// We don't have to go through all fragments, the JdkSettings are platform-agnostic.
val AmperModule.internalSettings: InternalSettings get() = fragments.first { !it.isTest }.settings.internal

/**
 * Returns the internal settings for this module's test code.
 */
// We don't have to go through all fragments, the JdkSettings are platform-agnostic.
val AmperModule.testInternalSettings: InternalSettings get() = fragments.first { !it.isTest }.settings.internal
