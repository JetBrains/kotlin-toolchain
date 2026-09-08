/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jvm

import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.frontend.Fragment
import org.jetbrains.amper.frontend.findEffectiveJvmMainClass
import kotlin.io.path.pathString

/**
 * Finds the fully qualified name of the JVM main class for these fragments.
 *
 * This function first looks for an explicit main class in the user settings.
 * If not found, the sources are inspected to find the main class based on the Amper naming convention.
 * If even this doesn't yield anything, we throw with a user-readable error explaining the problem.
 */
internal fun List<Fragment>.getEffectiveJvmMainClass(): String {
    require(isNotEmpty()) { "The fragment list is empty, cannot find the main class" }
    val module = first().module
    require(module.type.isApplication()) { "Attempting to get the main class for a non-application module" }

    val effectiveMainClass = findEffectiveJvmMainClass()

    if (effectiveMainClass == null) {
        userReadableError(
            "The JVM main class was not found for application module `${module.userReadableName}` in any of the " +
                    "following source directories:\n${flatMap { it.sourceRoots }.joinToString("\n") { "- ${it.pathString}" }}\n" +
                    "Make sure a main.kt file is present in your sources with a valid `main` function, or declare " +
                    "the fully-qualified main class explicitly with `settings.jvm.mainClass` in your module file."
        )
    }
    return effectiveMainClass
}
