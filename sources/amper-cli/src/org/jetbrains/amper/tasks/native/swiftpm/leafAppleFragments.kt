/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.flow.toResolutionPlatform
import org.jetbrains.amper.frontend.isDescendantOf

internal fun AmperModule.leafAppleFragments(): List<LeafFragment> = leafFragments.filter {
    it.platform.isDescendantOf(Platform.APPLE)
}

/**
 * The `KonanTarget` name of this platform, as SwiftPM metadata refers to its targets.
 *
 * The very same names are used by the KGP publication, so both the metadata we publish and the one we read must go
 * through this function to stay in sync.
 */
internal fun Platform.konanTargetName(): String =
    toResolutionPlatform()?.nativeTarget ?: error("$this is not a Kotlin/Native platform")
