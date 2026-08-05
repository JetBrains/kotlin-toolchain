/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.maven.publish

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.frontend.Platform

/**
 * The coordinates to use in publications instead of the declared ones, per platform.
 *
 * Each leaf platform of a module resolves its own variant of a given multiplatform dependency (for instance,
 * `kotlinx-coroutines-core` resolves to `kotlinx-coroutines-core-jvm` for the JVM platform, and to
 * `kotlinx-coroutines-core-linuxx64` for linuxX64), so the overrides of all platforms must be kept apart.
 */
@Serializable
class PublicationCoordinatesOverrides(
    private val overrides: List<PublicationCoordinatesOverride> = emptyList(),
) {
    // maps with non-primitive keys cannot be serialized as JSON as-is
    @Transient
    private val overridesMap = overrides.associate { (it.platform to it.originalCoordinates) to it.variantCoordinates }

    /**
     * Creates a new [PublicationCoordinatesOverrides] instance containing all overrides from this and [other].
     */
    operator fun plus(other: PublicationCoordinatesOverrides) =
        PublicationCoordinatesOverrides(overrides = overrides + other.overrides)

    /**
     * Returns the coordinates to use when publishing a dependency on [coords] for the given [platform].
     *
     * The given [coords] are returned as-is if this platform doesn't override them. This is in particular the case
     * for [Platform.COMMON], which is not resolved as such, and thus must keep the platform-independent coordinates.
     */
    fun actualCoordinatesFor(coords: MavenCoordinates, platform: Platform): MavenCoordinates =
        overridesMap[platform to coords] ?: coords
}

@Serializable
class PublicationCoordinatesOverride(
    /**
     * The leaf platform for which the dependency was resolved to [variantCoordinates].
     */
    val platform: Platform,
    val originalCoordinates: MavenCoordinates,
    val variantCoordinates: MavenCoordinates,
)

/**
 * Merges these [PublicationCoordinatesOverrides] into a single instance.
 */
fun Iterable<PublicationCoordinatesOverrides>.merge() =
    fold(PublicationCoordinatesOverrides(), PublicationCoordinatesOverrides::plus)
