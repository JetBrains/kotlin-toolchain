/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LocalSwiftPMDependencyNotation
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.isDescendantOf

/**
 * The notations declaring the local Swift packages that this module depends on.
 *
 * Only non-test Apple fragments are taken into account: SwiftPM dependencies are only supported on Apple platforms
 * (see [SwiftPMDependencyInNonApplePlatformFactory]), and test-only ones are rejected while reading the module
 * (see `diagnoseTestOnlySwiftPMDependencies`). SwiftPM dependencies are duplicated in every fragment that sees them,
 * hence the deduplication by path.
 */
internal fun AmperModule.declaredLocalSwiftPackages(): List<LocalSwiftPMDependencyNotation> = fragments
    .filter { !it.isTest && it.platforms.all { platform -> platform.isDescendantOf(Platform.APPLE) } }
    .flatMap { it.externalDependencies }
    .filterIsInstance<LocalSwiftPMDependencyNotation>()
    .distinctBy { it.swiftPMDependency.absolutePath }
