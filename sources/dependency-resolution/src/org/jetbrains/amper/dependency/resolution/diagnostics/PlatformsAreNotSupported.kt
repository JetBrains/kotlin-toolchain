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

/**
 * This diagnostic represent the situation where some subset of requested platforms are not supported
 * by library dependency.
 * It is always reported for the direct dependencies, and it may or may not be reported for transitive ones.
 *
 * Mismatching transitive dependencies are allowed because libraries with such deps exist in a wild:
 *   -- KMP libraries referencing non-KMP (pom.xml only) libraries
 *      (`io.kotest:kotest-framework-engine:5.9.1` declares `org.jetbrains.kotlin:kotlin-reflect:1.9.23`
 *      in 'desktopMain', a source set of its native variants);)
 *   -- a dependency of a source set that is visible for a shared source set only because
 *      one of its platforms publishes no source sets in the project structure metadata, as in KGP
 *      (`org.jetbrains.compose.ui:ui-util:1.12.1` declares `ui-uikit` in 'iosMain', visible for android+ios).
 *
 * Such a dependency doesn't break the build: it contributes nothing where it isn't supported:
 * - a JVM-only JAR is skipped by non-JVM compilations,
 * - a KMP library missing some platforms of a shared source set is not visible there,
 * The resolution for each platform reports the problems of its own dependencies.
 *
 * As a direct dependency is always reported, a library published by the Kotlin Toolchain never declares
 * such a dependency itself.
 */
@Serializable
class PlatformsAreNotSupported private constructor(
    val coordinates: MavenCoordinates,
    val requestedPlatforms: Set<ResolutionPlatform>,
    val supportedPlatforms: Set<ResolutionPlatform>,
    override val reportTransitive: Boolean = false,
) : Message {

    /**
     * @param alreadyReportedPlatforms the platforms the [dependency] is resolved for,
     * which are reported by another message, so they are not listed among unsupported ones
     */
    constructor(
        dependency: MavenDependency,
        supportedPlatforms: Set<ResolutionPlatform>,
        reportTransitive: Boolean,
        alreadyReportedPlatforms: Set<ResolutionPlatform> = emptySet(),
    ) : this(
        coordinates = dependency.coordinates,
        requestedPlatforms = dependency.resolutionConfig.platforms - alreadyReportedPlatforms,
        supportedPlatforms = supportedPlatforms,
        reportTransitive = reportTransitive,
    )

    companion object {
        const val ID = "platforms.are.not.supported"
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
}
