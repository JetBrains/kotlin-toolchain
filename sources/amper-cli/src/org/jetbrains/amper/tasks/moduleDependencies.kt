/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks

import io.opentelemetry.api.GlobalOpenTelemetry
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies
import org.jetbrains.amper.incrementalcache.IncrementalCache

/**
 * Returns a sequence of the transitive dependencies of this module on other local modules in the resolution
 * scope of the given [platform], [isTest] and [dependencyReason].
 * External maven dependencies are ignored, only local modules are returned.
 */
fun AmperModule.getModuleDependencies(
    isTest: Boolean,
    platform: Platform,
    dependencyReason: ResolutionScope,
    userCacheRoot: AmperUserCacheRoot,
    incrementalCache: IncrementalCache
) : Sequence<AmperModule> = with(ModuleDependencies) {
    getLocalModuleDependencies(isTest, platform, dependencyReason, userCacheRoot, incrementalCache,GlobalOpenTelemetry.get())
}

/**
 * Returns a sequence of the transitive dependencies of this module on other local modules in the resolution
 * scope of the given [platform], [isTest] and [dependencyReason].
 * External maven dependencies are ignored, only local modules are returned.
 */
context(cliContext: ProjectCliContext)
fun AmperModule.getModuleDependencies(
    isTest: Boolean,
    platform: Platform,
    dependencyReason: ResolutionScope,
) : Sequence<AmperModule> = with(ModuleDependencies) {
    getLocalModuleDependencies(
        isTest = isTest,
        platform = platform,
        dependencyReason = dependencyReason,
        userCacheRoot = cliContext.userCacheRoot,
        incrementalCache = cliContext.incrementalCache,
        openTelemetry = cliContext.openTelemetry,
    )
}
