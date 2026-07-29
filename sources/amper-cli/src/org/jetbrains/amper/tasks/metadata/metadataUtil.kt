/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.metadata

import kotlinx.serialization.json.Json
import org.jetbrains.amper.dependency.resolution.PlatformType
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.attributes.Category
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.DefaultScopedNotation
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.dr.resolver.flow.dependenciesAvailableForConsumerClasspath
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.dr.resolver.toDrMavenCoordinates
import org.jetbrains.amper.maven.publish.PublicationCoordinatesOverrides
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.gradle.module.metadata.format.ArtifactSelector
import org.jetbrains.gradle.module.metadata.format.Dependency
import org.jetbrains.gradle.module.metadata.format.ThirdPartyCompatibility
import org.jetbrains.gradle.module.metadata.format.Version

internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Contains sets of platforms for which metadata compilation is not executed.
 */
private val noMetadataCompilationSets: Set<Set<Platform>> = setOf(
    setOf(Platform.JVM, Platform.ANDROID),
    setOf(Platform.WASM_JS, Platform.WASM_WASI),
)

internal fun AmperModule.allMetadataFragments() =
    fragments.asSequence().allMetadataFragments()

internal fun Sequence<Fragment>.allMetadataFragments() =
    filter { !it.isTest && it.platforms.isMetadataCompilationRequired() }

internal fun Set<Platform>.isMetadataCompilationRequired() = size > 1 && this !in noMetadataCompilationSets

internal fun ResolutionScope.toVariantSuffix(): String = when (this) {
    ResolutionScope.COMPILE -> "Api"
    ResolutionScope.RUNTIME -> "Runtime"
}

internal fun Fragment.sourceSetName(): String {
    check(!isTest) { "Test fragments are not a part of all metadata, only main fragments are allowed" }
    return if (name.endsWith("Main", ignoreCase = true)) name else "${name}Main"
}

/**
 * The name of the directory inside the all-metadata JAR that holds the commonized cinterop klibs of [fragment].
 *
 * The very same name is published in the `sourceSetCInteropMetadataDirectory` field of the Kotlin project structure
 * metadata, this is how consumers locate those klibs. Both places must use this function to stay in sync.
 */
internal fun cinteropMetadataDirectoryName(fragment: Fragment) = "${fragment.sourceSetName()}-cinterop"

/**
 * Returns dependencies of this [Fragment] reachable from the consumer compilation classpath,
 * For instance, COMPILE classpath of the consumer should not know about non-exported
 * compile dependencies of this fragment (in non-native case).
 */
internal fun Fragment.classPathForApiMetadata() =
    dependenciesAvailableForConsumer(scope = ResolutionScope.COMPILE)

/**
 * Returns dependencies of this [Fragment] reachable from the consumer side,
 * For instance, COMPILE classpath of the consumer should not know about non-exported
 * compile dependencies of this fragment (in non-native case).
 * At the same time, RUNTIME dependencies of the [Fragment] should be available on the runtime classpath of the consumer.
 */
internal fun Fragment.dependenciesAvailableForConsumer(scope: ResolutionScope) =
    dependenciesAvailableForConsumerClasspath(
        platforms = platforms.map { it.toResolutionPlatform()!! }.toSet(),
        scope = scope,
        includeNonExportedNative = true,
    )

internal fun getApplicableVariantScopes(leafFragment: LeafFragment): List<ResolutionScope> = buildList {
    add(ResolutionScope.COMPILE)
    if (leafFragment.platform.toResolutionPlatform()?.type != PlatformType.NATIVE) {
        add(ResolutionScope.RUNTIME)
    }
}

internal fun Notation.toVariantDependency(
    platform: Platform,
    overrides: PublicationCoordinatesOverrides? = null,
): Dependency? = when (this) {
    is MavenDependencyBase -> toVariantDependency(platform, overrides)
    is LocalModuleDependency -> toVariantDependency(platform)
    is LocalSwiftPMDependencyNotation,
    is RemoteSwiftPMDependencyNotation -> null // FIXME: Support SwiftPM metadata variant publication
    is DefaultScopedNotation -> error("Dependency type ${this::class.simpleName} is not supported for .module publication")
}

private fun LocalModuleDependency.toVariantDependency(platform: Platform): Dependency {
    val coords = module.publicationCoordinates(platform)

    val dependency = Dependency(
        group = coords.groupId,
        module = coords.artifactId,
        version = Version(requires = coords.version),
    )
    return dependency
}

private fun MavenDependencyBase.toVariantDependency(
    platform: Platform,
    overrides: PublicationCoordinatesOverrides?,
): Dependency {
    val isBom = this@toVariantDependency is BomDependency

    val effectiveCoordinates = toDrMavenCoordinates().let {
        overrides?.actualCoordinatesFor(it, platform) ?: it
    }

    val dependency = Dependency(
        group = effectiveCoordinates.groupId,
        module = effectiveCoordinates.artifactId,
        version = Version(requires = effectiveCoordinates.version),
        thirdPartyCompatibility = if (effectiveCoordinates.classifier != null || effectiveCoordinates.packagingType != null)
            ThirdPartyCompatibility(
                artifactSelector = ArtifactSelector(
                    classifier = effectiveCoordinates.classifier,
                    type = effectiveCoordinates.packagingType,
                    // todo (AB): Should we support extension field here?
//                    extension = resolveArtifactExtension(coordinates.packagingType).
                )
            ) else null,
        attributes = buildMap {
            if (isBom) {
                this[Category.name] = Category.Platform.value
            }
        },
        endorseStrictVersions = if (isBom) true else null,
    )

    return dependency
}
