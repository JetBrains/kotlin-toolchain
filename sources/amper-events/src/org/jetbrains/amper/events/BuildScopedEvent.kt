/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events

import kotlinx.serialization.Serializable
import org.jetbrains.amper.events.payload.TaskMonikerSpec

/**
 * An event that happens in the context of a *build*.
 *
 * ### Build
 * Build is an abstract concept that can execute a
 * [predefined number][GlobalScopedEvent.BuildStarted.totalTasksCount] of tasks.
 *
 * It's not limited to the actual `kotlin build` command which physically builds something.
 * Any task graph execution is counted as a "build".
 *
 * @see GlobalScopedEvent
 * @see OperationScopedEvent
 */
@Serializable
sealed interface BuildScopedEvent : Event {
    /**
     * A new task has started under the build.
     */
    @Serializable
    data class TaskStarted(
        val id: TaskExecutionId,
        /**
         * A structured spec describing the task's moniker.
         * Every integrator can format the actual label in their own preferred way.
         */
        val monikerSpec: TaskMonikerSpec,
        /**
         * Whether this task requires full control of the interactive terminal.
         * Thus, any sticky widgets should be hidden.
         */
        val isInteractive: Boolean = false,
    ) : BuildScopedEvent

    /**
     * A new task is started inside the build.
     */
    @Serializable
    data class TaskFinished(
        val id: TaskExecutionId,
    ) : BuildScopedEvent

    /**
     * Events that occur in the task with the given [id] are emitted via this wrapper.
     * Mind that the nested events are already [OperationScopedEvent]s.
     */
    @Serializable
    data class TaskEvent(
        val id: TaskExecutionId,
        val event: OperationScopedEvent,
    ) : BuildScopedEvent
}
