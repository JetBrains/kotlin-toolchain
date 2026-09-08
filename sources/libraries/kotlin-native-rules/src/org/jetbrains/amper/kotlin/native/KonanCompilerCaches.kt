/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.kotlin.native

import java.util.Properties

/**
 * The targets for which the Kotlin/Native compiler supports compiler caches when running on the given [host],
 * and for which caches are enabled by default.
 *
 * Targets that the distribution marks as requiring an explicit opt-in are excluded because their caches are not
 * considered stable enough to be turned on by default.
 *
 * This is the equivalent of KGP's `KonanPropertiesBuildService.defaultCacheKindForTarget` returning `STATIC`.
 *
 * The relevant part of `konan.properties` looks like this (the lists are per-host, and empty for hosts without
 * cache support):
 * ```properties
 * cacheableTargets.macos_arm64 = \
 *   macos_arm64 \
 *   ios_simulator_arm64 \
 *   ios_arm64
 * cacheableTargets.mingw_x64 =
 * optInCacheableTargets =
 * ```
 */
fun KonanDistribution.cacheableTargets(host: KonanPlatform): Set<KonanPlatform> =
    konanProperties.resolvedPlatformList("cacheableTargets", host) -
            konanProperties.resolvedPlatformList("optInCacheableTargets", host)

/**
 * Whether the Kotlin/Native compiler supports compiler caches for [target] when running on the given [host].
 */
fun KonanDistribution.supportsCompilerCachesFor(target: KonanPlatform, host: KonanPlatform): Boolean =
    target in cacheableTargets(host)

private fun Properties.resolvedPlatformList(key: String, host: KonanPlatform): Set<KonanPlatform> =
    resolvablePropertyList(key, suffix = host.nameForCompiler).mapTo(mutableSetOf(), ::KonanPlatform)

/**
 * Returns the whitespace-separated list of values of the `<key>.<suffix>` property, falling back to `<key>` when
 * there is no such host-specific property.
 *
 * Values prefixed with `$` are references to other properties and are replaced by the (recursively resolved) list
 * of values of the referenced property. This mirrors `resolvablePropertyList` from the Kotlin/Native distribution,
 * which is what KGP uses to read these same properties.
 */
private fun Properties.resolvablePropertyList(
    key: String,
    suffix: String,
    alreadyVisitedKeys: Set<String> = emptySet(),
): List<String> {
    if (key in alreadyVisitedKeys) {
        return [] // guards against reference cycles in a malformed konan.properties
    }
    val rawValue = getProperty("$key.$suffix") ?: getProperty(key) ?: return []
    return rawValue.split(WHITESPACE).filter { it.isNotEmpty() }.flatMap { value ->
        val referencedKey = value.removePrefix("$")
        if (referencedKey == value) {
            [value]
        } else {
            resolvablePropertyList(referencedKey, suffix, alreadyVisitedKeys + key)
        }
    }
}

private val WHITESPACE = Regex("\\s+")
