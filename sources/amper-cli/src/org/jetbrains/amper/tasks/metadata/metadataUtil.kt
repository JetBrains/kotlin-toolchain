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
import org.jetbrains.amper.frontend.MavenDependencyBase
import org.jetbrains.amper.frontend.Notation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.flow.dependenciesAvailableForConsumerClasspath
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
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

internal fun AmperModule.allMetadataFragments() =
    fragments.asSequence().allMetadataFragments()

internal fun Sequence<Fragment>.allMetadataFragments() =
    filter { it.platforms.size > 1 && !it.isTest }

internal fun ResolutionScope.toVariantSuffix(): String = when (this) {
    ResolutionScope.COMPILE -> "Api"
    ResolutionScope.RUNTIME -> "Runtime"
}

internal fun Fragment.sourceSetName(): String {
    check(!isTest) { "Test fragments are not a part of all metadata, only main fragments are allowed" }
    return if (name.endsWith("Main")) name else "${name}Main"
}

/**
 * Returns dependencies reachable from the consumer side,
 * For instance, COMPILE classpath of the consumer should not know about non-exported
 * compile dependencies of this fragment (in non-native case)
 */
internal fun Fragment.classPathForApiMetadata() = dependenciesAvailableForConsumerClasspath(
    platforms = platforms.map { it.toResolutionPlatform()!! }.toSet(),
    scope = ResolutionScope.COMPILE,
    includeNonExportedNative = true,
)

    internal fun getApplicableVariantScopes(leafFragment: LeafFragment): List<ResolutionScope> = buildList {
    add(ResolutionScope.COMPILE)
    if (leafFragment.platform.toResolutionPlatform()?.type != PlatformType.NATIVE) {
        add(ResolutionScope.RUNTIME)
    }
}

internal fun Notation.toVariantDependency(
    platform: Platform
): Dependency = when (this) {
    is MavenDependencyBase -> toVariantDependency()
    is LocalModuleDependency -> toVariantDependency(platform)
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

private fun MavenDependencyBase.toVariantDependency(): Dependency {
    val isBom = this@toVariantDependency is BomDependency

    val dependency = Dependency(
        group = coordinates.groupId,
        module = coordinates.artifactId,
        version = Version(requires = coordinates.version?.value),
        thirdPartyCompatibility = if (coordinates.classifier != null || coordinates.packagingType != null)
            ThirdPartyCompatibility(
                artifactSelector = ArtifactSelector(
                    classifier = coordinates.classifier,
                    type = coordinates.packagingType,
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
