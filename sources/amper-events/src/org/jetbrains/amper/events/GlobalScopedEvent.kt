/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events

import kotlinx.serialization.Serializable

/**
 * An event that happens in the root (global) event scope, like CLI invocation.
 *
 * Example of the event flow:
 * ```
 * - BuildStarted(id=1)
 * - BuildEvent(id=1):
 *     BuildScopedEvent.TaskStarted(id=2, "task-1")
 * - BuildEvent(id=1):
 *     BuildScopedEvent.TaskEvent(id=2):
 *       SomeDomainEvent("hello from task-1")
 * - BuildEvent(id=1):
 *     BuildScopedEvent.TaskEvent(id=2):
 *       OperationScopedEvent.Started(id=3, "task-1/op-1")
 * - BuildEvent(id=1):
 *     BuildScopedEvent.TaskEvent(id=2):
 *       OperationScopedEvent.ChildOperationEvent(id=3):
 *         SomeDomainEvent("hello from task-1/op-1")
 * - BuildEvent(id=1):
 *     BuildScopedEvent.TaskEvent(id=2):
 *       OperationScopedEvent.Finished(id=3)
 * - BuildEvent(id=1):
 *     BuildScopedEvent.TaskFinished(id=2)
 * - BuildFinished(id=1)
 * ```
 *
 * @see BuildScopedEvent
 */
@Serializable
sealed interface GlobalScopedEvent : Event {
    /**
     * New build is started
     */
    @Serializable
    data class BuildStarted(
        val id: BuildId,
        /**
         * Total task count that is planned to be started in this build.
         */
        val totalTasksCount: Int,
    ) : GlobalScopedEvent

    /**
     * Build is finished.
     */
    @Serializable
    data class BuildFinished(
        val id: BuildId,
    ) : GlobalScopedEvent

    /**
     * Events that occur in the build with the given [id] are emitted via this wrapper.
     * Mind that the nested events are already [BuildScopedEvent]s.
     */
    @Serializable
    data class BuildEvent(
        val id: BuildId,
        val event: BuildScopedEvent,
    ) : GlobalScopedEvent
}
