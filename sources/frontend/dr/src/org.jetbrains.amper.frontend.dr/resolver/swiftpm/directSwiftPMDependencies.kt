/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver.swiftpm

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.BomDependency
import org.jetbrains.amper.frontend.DefaultScopedNotation
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.MavenDependency
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.RemoteSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.SwiftPMDependencyNotation
import org.jetbrains.amper.frontend.isDescendantOf
import org.jetbrains.amper.swiftpm.SwiftPMDependency

/**
 * A SwiftPM dependency declared by a module, together with all the [notations] that declared it.
 */
class DirectSwiftPMDependency(
    val swiftPMDependency: SwiftPMDependency,
    val notations: Set<SwiftPMDependencyNotation>,
)

/**
 * The SwiftPM dependencies declared by this module.
 *
 * SwiftPM dependencies are initially duplicated (as regular dependencies) in every fragment. Here we deduplicate
 * them by absolutePath/repository and apply platform constraints if a SwiftPM dependency was consumed in a qualified
 * fragment.
 */
fun AmperModule.directSwiftPMDependencies(): List<DirectSwiftPMDependency> {
    class SwiftPMDependencyPlatformsAndNotations(
        val platforms: Set<SwiftPMDependency.Platform>,
        val notations: Set<SwiftPMDependencyNotation>,
    )

    val appleFragments = fragments.filter { it.platforms.all { it.isDescendantOf(Platform.APPLE) } }
    val modulePlatformConstraints = appleFragments.flatMap { it.platforms }.map { it.toSwiftPMPlatform() }.toSet()
    val platformsByDeclaredSwiftPMDependency = mutableMapOf<SwiftPMDependency, SwiftPMDependencyPlatformsAndNotations>()
    // Deduplicate SwiftPM dependencies coming from notation
    appleFragments.forEach { appleFragment ->
        val fragmentSwiftPMPlatforms = appleFragment.platforms.map { it.toSwiftPMPlatform() }.toSet()
        appleFragment.externalDependencies.forEach { notation ->
            val dependency = when (notation) {
                is LocalSwiftPMDependencyNotation -> notation.swiftPMDependency
                is RemoteSwiftPMDependencyNotation -> notation.swiftPMDependency
                is DefaultScopedNotation,
                is BomDependency,
                is MavenDependency -> return@forEach
            }
            val existing = platformsByDeclaredSwiftPMDependency[dependency]
            if (existing != null) {
                val right = fragmentSwiftPMPlatforms - existing.platforms
                if (right.isNotEmpty()) {
                    platformsByDeclaredSwiftPMDependency[dependency] = SwiftPMDependencyPlatformsAndNotations(
                        platforms = right,
                        notations = existing.notations + notation,
                    )
                }
            } else {
                platformsByDeclaredSwiftPMDependency[dependency] = SwiftPMDependencyPlatformsAndNotations(
                    platforms = fragmentSwiftPMPlatforms,
                    notations = setOf(notation),
                )
            }
        }
    }

    val swiftPMDependencyByIdentifier = mutableMapOf<Any, DirectSwiftPMDependency>()
    platformsByDeclaredSwiftPMDependency.toList().forEach {
        val swiftPMDependency = it.first
        val platformsAndNotations = it.second
        val id = when (swiftPMDependency) {
            is SwiftPMDependency.Local -> swiftPMDependency.absolutePath
            is SwiftPMDependency.Remote -> swiftPMDependency.repository to swiftPMDependency.version
        }
        val effectivePlatformConstraints = if (platformsAndNotations.platforms == modulePlatformConstraints) {
            null
        } else {
            platformsAndNotations.platforms
        }

        val existingProducts = swiftPMDependencyByIdentifier[id]?.swiftPMDependency?.products ?: emptyList()
        val existingNotations = swiftPMDependencyByIdentifier[id]?.notations ?: emptySet()
        val combinedProductsList = existingProducts + swiftPMDependency.products.map {
            it.copy(
                platformConstraints = effectivePlatformConstraints?.toList()
            )
        }

        swiftPMDependencyByIdentifier[id] = DirectSwiftPMDependency(
            swiftPMDependency = when (swiftPMDependency) {
                is SwiftPMDependency.Local -> swiftPMDependency.copy(products = combinedProductsList)
                is SwiftPMDependency.Remote -> swiftPMDependency.copy(products = combinedProductsList)
            },
            notations = existingNotations + platformsAndNotations.notations,
        )
    }

    return swiftPMDependencyByIdentifier.values.toList()
}

private fun Platform.toSwiftPMPlatform(): SwiftPMDependency.Platform = when {
    isDescendantOf(Platform.IOS) -> SwiftPMDependency.Platform.iOS
    isDescendantOf(Platform.MACOS) -> SwiftPMDependency.Platform.macOS
    isDescendantOf(Platform.TVOS) -> SwiftPMDependency.Platform.tvOS
    isDescendantOf(Platform.WATCHOS) -> SwiftPMDependency.Platform.watchOS
    else -> error("Non Apple platform $this")
}
