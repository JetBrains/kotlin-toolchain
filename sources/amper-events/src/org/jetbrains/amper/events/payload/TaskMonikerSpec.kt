/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.payload

import kotlinx.serialization.Serializable

/**
 * A structure that describes the task label.
 *
 * This structural approach was chosen over a simple string because every client is free to choose how they are going
 *   to present the task moniker depending on their use-case.
 */
@Serializable
sealed interface TaskMonikerSpec {
    @Serializable
    data class ProjectScoped(
        val operationMoniker: String,
    ) : TaskMonikerSpec

    @Serializable
    data class ModuleScoped(
        val operationMoniker: String,
        val moduleName: String,
    ) : TaskMonikerSpec

    @Serializable
    data class CompilationScoped(
        val operationMoniker: String,
        val moduleName: String,
        val platform: String,
        val isTest: Boolean = false,
        val buildType: String? = null,
    ) : TaskMonikerSpec

    @Serializable
    data class FragmentScoped(
        val operationMoniker: String,
        val moduleName: String,
        val fragmentName: String,
    ) : TaskMonikerSpec

    @Serializable
    data class FromPlugin(
        val moduleName: String,
        val pluginId: String,
        val name: String,
    ) : TaskMonikerSpec
}
