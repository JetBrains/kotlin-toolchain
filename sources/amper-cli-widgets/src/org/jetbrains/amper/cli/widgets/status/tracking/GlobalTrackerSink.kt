/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status.tracking

import org.jetbrains.amper.cli.widgets.status.BuildState
import org.jetbrains.amper.events.BuildId
import org.jetbrains.amper.events.GlobalScopedEvent
import org.jetbrains.amper.events.sink.GlobalEventSink
import java.util.concurrent.ConcurrentHashMap

internal class GlobalTrackerSink(
    private val delegate: StatusTrackerDelegate,
) : GlobalEventSink {
    private val statesMap = ConcurrentHashMap<BuildId, BuildStateTrackerSink>()

    val builds: Collection<BuildState> get() = statesMap.values

    override fun emit(event: GlobalScopedEvent) = when (event) {
        is GlobalScopedEvent.BuildStarted -> {
            statesMap[event.id] = BuildStateTrackerSink(
                buildId = event.id,
                totalTasksCount = event.totalTasksCount,
                delegate = delegate,
            )
            delegate.onStateUpdated()
        }
        is GlobalScopedEvent.BuildFinished -> {
            checkNotNull(statesMap.remove(event.id)) {
                "Inconsistent $event: no such build"
            }
            delegate.onStateUpdated()
        }
        is GlobalScopedEvent.BuildEvent -> {
            checkNotNull(statesMap[event.id]) {
                "Inconsistent $event: no such build"
            }.emit(event.event)
        }
    }
}
