/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.maven.publish

import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.mavenCoordinatesTrimmed
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.schema.ProductType

/**
 * Returns the Maven coordinates that should be used to publish this module's artifacts for the given platform.
 *
 * * For `jvm/lib` modules, it's the standard group id, artifact id, and version, without the `-jvm` suffix.
 * * For `kmp/lib` modules, the common platform gets the base artifact ID, and leaf platforms use a platform suffix.
 */
fun AmperModule.publicationCoordinates(platform: Platform): MavenCoordinates = when {
    platform == Platform.COMMON || !isMultiplatformPublication() -> rootPublicationCoordinates()
    platform.isLeaf -> kmpLeafPlatformPublicationCoordinates(platform)
    else -> error("Cannot generate Maven coordinates for $platform: only COMMON and leaf platforms are supported")
}

fun AmperModule.isMultiplatformPublication() = type.supportedPlatforms.size > 1

private fun AmperModule.kmpLeafPlatformPublicationCoordinates(platform: Platform): MavenCoordinates {
    val fragment = leafFragments.singleOrNull { !it.isTest && platform in it.platforms }
        ?: error("Cannot generate Maven coordinates for module '$userReadableName' with platform $platform: expected " +
                "a single leaf fragment supporting this platform, but got " +
                "${leafFragments.filter { platform in it.platforms }.map { it.name }}")

    // the leaf fragment should inherit publication settings from parents, no need to browse again
    return fragment.mavenCoordinates(artifactIdSuffix = "-${platform.schemaValue.lowercase()}")
}

private fun AmperModule.rootPublicationCoordinates(): MavenCoordinates {
    val commonFragment = fragments.find { !it.isTest && it.fragmentDependencies.isEmpty() }
        ?: error("Cannot generate root Maven coordinates for module '$userReadableName': no root fragment")

    return commonFragment.mavenCoordinates(artifactIdSuffix = "")
}

// todo (AB): [AMPER-5245] Support publishing with classifier.
private fun Fragment.mavenCoordinates(artifactIdSuffix: String): MavenCoordinates = mavenCoordinatesTrimmed(
    groupId = settings.publishing.group
        ?: error("Missing 'group' in publishing settings of fragment '${name}' of module '${module.userReadableName}'"),
    artifactId = (settings.publishing.artifactId ?: module.userReadableName.lowercase()) + artifactIdSuffix,
    version = settings.publishing.version
        ?: error("Missing 'version' in publishing settings of fragment '${name}' of module '${module.userReadableName}'")
)
