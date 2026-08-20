/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform

fun formatModules(modules: Collection<AmperModule>) =
    modules.map { it.userReadableName }.sorted().joinToString(" ")

fun formatPlatforms(platforms: Collection<Platform>) =
    platforms.map { it.pretty }.sorted().joinToString(" ")

fun formatModulePlatforms(moduleToRun: AmperModule): String {
    return formatPlatforms(moduleToRun.leafPlatforms)
}

fun Model.getModuleByName(moduleName: String) = getModuleByNameOrNull(moduleName) ?: userReadableError(
    "Unable to resolve module by name '$moduleName'.\n\n" +
            "Available modules: ${formatModules(modules)}"
)
