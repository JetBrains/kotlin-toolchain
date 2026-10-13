/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.sink

import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.Event
import org.jetbrains.amper.events.GlobalScopedEvent
import org.jetbrains.amper.events.OperationScopedEvent

/**
 * [EventSink] that unwraps and proxies all the domain events to the [sink].
 */
class DomainEventFlatteningSink(
    val sink: EventSink<OperationScopedEvent.DomainEvent>,
) : EventSink<Event> {
    override fun emit(event: Event) {
        when (event) {
            is GlobalScopedEvent.BuildEvent -> emit(event.event)
            is BuildScopedEvent.TaskEvent -> emit(event.event)
            is OperationScopedEvent.ChildOperationEvent -> emit(event.event)
            is OperationScopedEvent.DomainEvent -> sink.emit(event)
            else -> Unit // Other events can't contain domain events down their hierarchy
        }
    }
}