/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.sink

import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.OperationId
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.TaskExecutionId

/**
 * Allows consuming [OperationScopedEvent]s.
 * Not an interface to support proper contravariance.
 */
typealias OperationEventSink = EventSink<OperationScopedEvent>

/**
 * Constructs **task** operation sink using the build as a parent.
 * All the events routed through the created sink are wrapped into an [BuildScopedEvent.TaskEvent].
 *
 * @param buildSink build event sink to delegate wrapped events to
 * @param id new task execution id
 *
 * @see BuildScopedEvent.TaskStarted
 * @see BuildScopedEvent.TaskFinished
 */
fun OperationEventSink(
    buildSink: BuildEventSink,
    id: TaskExecutionId,
): OperationEventSink = object : OperationEventSink {
    override fun emit(event: OperationScopedEvent) {
        buildSink.emit(BuildScopedEvent.TaskEvent(id, event))
    }
}

/**
 * Constructs a nested [OperationEventSink] for an *operation*, using the parent operation's id.
 * All the events routed through the created sink are wrapped into an [OperationScopedEvent.ChildOperationEvent].
 *
 * @param parentOperationSink parent operation sink to delegate wrapped events to
 * @param id new operation id
 *
 * @see OperationScopedEvent.Started
 * @see OperationScopedEvent.Started
 */
fun OperationEventSink(
    parentOperationSink: OperationEventSink,
    id: OperationId,
): OperationEventSink = object : OperationEventSink {
    override fun emit(event: OperationScopedEvent) {
        parentOperationSink.emit(OperationScopedEvent.ChildOperationEvent(id, event))
    }
}
