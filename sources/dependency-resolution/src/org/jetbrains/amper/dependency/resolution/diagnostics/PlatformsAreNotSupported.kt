/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.diagnostics

import kotlinx.serialization.Serializable
import org.jetbrains.amper.dependency.resolution.DependencyResolutionBundle
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenDependency
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.annotations.Nls

@Serializable
class PlatformsAreNotSupported private constructor(
    val coordinates: MavenCoordinates,
    val requestedPlatforms: Set<ResolutionPlatform>,
    val supportedPlatforms: Set<ResolutionPlatform>,
) : Message {

    /**
     * @param alreadyReportedPlatforms the platforms the [dependency] is resolved for,
     * which are reported by another message, so they are not listed among unsupported ones
     */
    constructor(
        dependency: MavenDependency,
        supportedPlatforms: Set<ResolutionPlatform>,
        alreadyReportedPlatforms: Set<ResolutionPlatform> = emptySet(),
    ) : this(dependency.coordinates, dependency.resolutionConfig.platforms - alreadyReportedPlatforms, supportedPlatforms)

    companion object {
        const val ID = "platforms.are.not.supported"

        private val jvmPlatforms = setOf(ResolutionPlatform.JVM, ResolutionPlatform.ANDROID)
    }

    override val id: String = ID
    override val severity: Severity = Severity.ERROR
    override val message: @Nls String =
        DependencyResolutionBundle.message(
            id,
            unsupportedPlatforms.size,
            unsupportedPlatforms.map(ResolutionPlatform::pretty).sorted().joinToString(),
            coordinates,
        )

    override val details: @Nls String
        get() = buildString {
            append(
                DependencyResolutionBundle.message(
                    "platforms.are.not.supported.details",
                    supportedPlatforms.size,
                    supportedPlatforms.map(ResolutionPlatform::pretty).sorted().joinToString(),
                )
            )
        }

    val unsupportedPlatforms: Set<ResolutionPlatform>
        get() = requestedPlatforms - supportedPlatforms

    /**
     * A library supporting the requested platforms might declare a JVM-only dependency
     * (e.g., declared in a source set shared between JVM and native targets).
     * Such a dependency is simply not used on non-JVM platforms, there is nothing the user should fix.
     *
     * Any other unsupported platform makes the library unusable there, even if it is a transitive dependency.
     */
    override val reportTransitive: Boolean
        get() = !supportedPlatforms.all { it in jvmPlatforms }
}
