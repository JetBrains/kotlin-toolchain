/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics.helpers

import org.jetbrains.amper.frontend.schema.KotlinVersion

/**
 * The latest known stable Kotlin version, to decide whether we should mention beta versions in error messages.
 *
 * Example: the user sets release 25 with a too old Kotlin version. Even though the max JVM target 25 was introduced in
 * Kotlin `2.3.0-Beta2`, we don't advise users to bump to that version, and instead offer to bump to `2.3.0`.
 */
private val LatestKnownStableKotlinVersion = KotlinVersion("2.4.21")

/**
 * Returns the stable version matching this [KotlinVersion] if it was released, or this [KotlinVersion] as-is otherwise.
 */
internal fun KotlinVersion.toStable(): KotlinVersion {
    if (this >= LatestKnownStableKotlinVersion) {
        return this
    }
    val match = Regex("""(?<stable>\d+\.\d+\.\d+)(-.*)?""").matchEntire(notation) ?: return this
    val stableNotation = match.groups["stable"]?.value ?: error("Regex matched by 'stable' group is absent")
    return KotlinVersion(stableNotation)
}