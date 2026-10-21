/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies

/**
 * Returns a sequence of the transitive dependencies of this module on other local modules in the resolution
 * scope of the given [platform], [isTest] and [dependencyReason].
 * External maven dependencies are ignored, only local modules are returned.
 */
fun ModuleDependencies.getModuleDependencies(
    isTest: Boolean,
    platform: Platform,
    dependencyReason: ResolutionScope,
) : Sequence<AmperModule> = with(ModuleDependencies) {
    getLocalModuleDependencies(
        isTest = isTest,
        platform = platform,
        dependencyReason = dependencyReason,
    )
}
