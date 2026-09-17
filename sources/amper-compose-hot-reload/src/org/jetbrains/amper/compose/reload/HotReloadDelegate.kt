/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compose.reload

import kotlinx.coroutines.Deferred

/**
 * An interface for the [HotReloadLoop] integrators.
 */
interface HotReloadDelegate<Context : HotReloadProjectContext> {
    /**
     * Reads the [org.jetbrains.amper.frontend.Model] and creates the [HotReloadLoop.State] object with the necessary entities.
     * If there are non-internal errors during this stage, they should be returned as a [Result.failure].
     */
    suspend fun readModel(): Result<HotReloadLoop.State<Context>>

    /**
     * Builds and runs the [application][HotReloadLoop.State.hotApp] with the hot-reload agent that
     * is expected to connect to the [orchestrationPort].
     */
    suspend fun runApplication(
        state: HotReloadLoop.State<Context>,
        orchestrationPort: Deferred<Int>,
    ): Result<Unit>

    /**
     * Recompile the classes for the [HotReloadLoop.State.hotApp].
     * @return list of actual changes to communicate to the hot-reload agent.
     */
    suspend fun rebuildClasses(
        state: HotReloadLoop.State<Context>,
    ): Result<HotReloadLoop.ClasspathChanges>
}
