/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.sink

import org.jetbrains.amper.events.Event

/**
 * An API for clients who support consuming events of type [E].
 *
 * Implementations must generally be thread-safe, as [emit] may be called from an arbitrary thread.
 *
 * If the implementation wants to do some non-trivial handling of the event,
 * it's a good idea to send the event to a buffered `Flow` or a `Channel` and process them asynchronously.
 * This allows the emitter to stay unblocked.
 *
 * @see plus
 * @see NoopEventSink
 * @see GlobalEventSink
 * @see BuildEventSink
 * @see OperationEventSink
 */
interface EventSink<in E : Event> {
    /**
     * Emits the [event] into the sink.
     */
    fun emit(event: E)
}
