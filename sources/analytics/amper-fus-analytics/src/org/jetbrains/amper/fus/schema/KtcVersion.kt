/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

private const val MAX_PATCH = 5

// We should keep this updated as new versions are released.
internal val KnownKtcVersions = ["LOCAL-SNAPSHOT"] + ["0.11", "0.12", "0.13", "0.14", "0.15", "0.16"]
    .flatMap { majorMinor ->
        ["$majorMinor.0-dev"] + (0..MAX_PATCH).map { patch -> "$majorMinor.$patch" }
    }

/**
 * Converts this full Kotlin Toolchain version to its representation in FUS event fields.
 *
 * String fields need to have all values known in advance, so this function trims the build number from dev versions,
 * keeping only the semver base and optionally the `-dev` marker.
 */
fun String.trimKtcVersionForFus(): String = when {
    this == "1.0-SNAPSHOT" -> "LOCAL-SNAPSHOT"
    "-dev-" in this -> substringBefore("-dev-") + "-dev"
    else -> this
}
