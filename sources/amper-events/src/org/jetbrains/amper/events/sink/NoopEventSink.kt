/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.sink

import org.jetbrains.amper.events.Event

/**
 * Trivial implementation for `EventSink` that does nothing.
 */
object NoopEventSink : EventSink<Event> {
    override fun emit(event: Event) = Unit
}
