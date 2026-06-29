/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.maven.publish

import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.publiation.mavenCoordinates
import org.jetbrains.amper.frontend.dr.resolver.publiation.rootPublicationCoordinates
import org.jetbrains.amper.frontend.schema.ProductType

/**
 * Returns the Maven coordinates that should be used to publish this module's artifacts for the given platform.
 *
 * * For `jvm/lib` modules, it's the standard group id, artifact id, and version, without the `-jvm` suffix.
 * * For `kmp/lib` modules, the common platform gets the base artifact ID, and leaf platforms use a platform suffix.
 */
fun AmperModule.publicationCoordinates(platform: Platform): MavenCoordinates = when {
    platform == Platform.COMMON || type == ProductType.JVM_LIB -> rootPublicationCoordinates()
    platform.isLeaf -> kmpLeafPlatformPublicationCoordinates(platform)
    else -> error("Cannot generate Maven coordinates for $platform: only COMMON and leaf platforms are supported")
}

private fun AmperModule.kmpLeafPlatformPublicationCoordinates(platform: Platform): MavenCoordinates {
    val fragment = leafFragments.singleOrNull { !it.isTest && platform in it.platforms }
        ?: error("Cannot generate Maven coordinates for module '$userReadableName' with platform $platform: expected " +
                "a single leaf fragment supporting this platform, but got " +
                "${leafFragments.filter { platform in it.platforms }.map { it.name }}")

    // the leaf fragment should inherit publication settings from parents, no need to browse again
    return fragment.mavenCoordinates(artifactIdSuffix = "-${platform.schemaValue.lowercase()}")
}