/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.aomBuilder

import org.jetbrains.amper.frontend.ClassBasedSet
import org.jetbrains.amper.frontend.ModulePart
import org.jetbrains.amper.frontend.ModuleTasksPart
import org.jetbrains.amper.frontend.classBasedSet
import org.jetbrains.amper.frontend.schema.Module

// These converters are needed only to prevent major code changes in the [gradle-integration].
// Parts should be replaced with schema model nodes in the future.

// FIXME Need to get rid of this `ModulePart` convention and
//  replace it by direct settings reading.
@Deprecated("Old mechanism. Use normal Kotlin API to expose things in the model")
fun Module.convertModuleParts(): ClassBasedSet<ModulePart<*>> {
    val parts = classBasedSet<ModulePart<*>>()
    parts += ModuleTasksPart(
        settings = tasks
            ?.mapValues { [_, settings] -> ModuleTasksPart.TaskSettings(dependsOn = settings.dependsOn?.map { it.value } ?: emptyList()) }
            ?: emptyMap(),
    )

    return parts
}
