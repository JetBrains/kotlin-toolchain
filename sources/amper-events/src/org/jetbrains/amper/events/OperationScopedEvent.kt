/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events

import kotlinx.serialization.Serializable

/**
 * An event that happens in the context of an *operation*.
 *
 * ### Operation
 * Operation is a *task* or a nested *operation* proper.
 *
 * ### Task
 * A task is the top-level *operation* that is started under a build.
 *
 * Operations can be started under the build using [BuildScopedEvent.TaskStarted] event,
 * or under the task/operation proper using [OperationScopedEvent.Started].
 *
 * Nested event sub-interfaces denote the events that can happen inside an operation.
 *
 * @see BuildScopedEvent
 */
@Serializable
sealed interface OperationScopedEvent : Event {
    /**
     * Another operation can be started inside the operation, recursively.
     */
    @Serializable
    data class Started(
        val id: OperationId,
        val moniker: String,
    ) : OperationScopedEvent

    /**
     * Finish event must correspond to a [Started] with the same [id].
     */
    @Serializable
    data class Finished(
        val id: OperationId,
    ) : OperationScopedEvent

    /**
     * A special "routing" event.
     * Events that happen inside the immediate child operation with the given [id] are wrapped into this event.
     *
     * @see org.jetbrains.amper.events.sink.OperationEventSink
     */
    @Serializable
    data class ChildOperationEvent(
        val id: OperationId,
        val event: OperationScopedEvent,
    ) : OperationScopedEvent

    /**
     * This is a non-sealed event interface that all useful events that can happen under an operation
     * should denote.
     *
     * This interface is a primary extension point of the event hierarchy.
     * Implementors can reside in their thematic modules/libraries.
     *
     * Implementors must be serializable as per [Event] contract.
     */
    interface DomainEvent : OperationScopedEvent
}
