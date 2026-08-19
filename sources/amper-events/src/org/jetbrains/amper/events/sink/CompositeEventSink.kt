/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.sink

import org.jetbrains.amper.events.Event

/**
 * Creates a composite [EventSink] for that supports consuming events [E].
 *
 * Events will be first emitted into `this` sink then into [another].
 */
operator fun <E : Event> EventSink<E>.plus(another: EventSink<E>): EventSink<E> =
    CompositeEventSink(unwrap() + another.unwrap())

private class CompositeEventSink<in E : Event>(
    val sinks: Collection<EventSink<E>>
): EventSink<E> {

    override fun emit(event: E) {
        sinks.forEach { it.emit(event) }
    }
}

private fun <E : Event> EventSink<E>.unwrap(): Collection<EventSink<E>> = when (this) {
    NoopEventSink -> []
    is CompositeEventSink -> sinks
    else -> [this]
}
