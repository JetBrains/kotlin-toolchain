/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events.sink

import org.jetbrains.amper.events.BuildId
import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.GlobalScopedEvent

/**
 * Allows consuming [BuildScopedEvent]s.
 * Not an interface to support proper contravariance.
 */
typealias BuildEventSink = EventSink<BuildScopedEvent>

/**
 * Constructs a nested [BuildEventSink] for an *operation*, using the parent operation's id.
 * All the events routed through the created sink are wrapped into an [GlobalScopedEvent.BuildEvent].
 *
 * @param globalSink global sink to delegate wrapped events to
 * @param id new build id
 *
 * @see GlobalScopedEvent.BuildStarted
 * @see GlobalScopedEvent.BuildFinished
 */
fun BuildEventSink(
    globalSink: GlobalEventSink,
    id: BuildId,
): BuildEventSink = object : BuildEventSink {
    override fun emit(event: BuildScopedEvent) {
        globalSink.emit(GlobalScopedEvent.BuildEvent(id, event))
    }
}
