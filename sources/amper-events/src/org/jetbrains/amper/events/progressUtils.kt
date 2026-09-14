/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.events

import org.jetbrains.amper.events.payload.ProgressState
import org.jetbrains.amper.events.sink.EventSink

/**
 * Emits the [progressState] into an event sink compatible with [OperationScopedEvent.ProgressUpdated] events.
 */
context(sink: EventSink<OperationScopedEvent.ProgressUpdated>)
fun emitProgressUpdated(progressState: ProgressState) {
    sink.emit(OperationScopedEvent.ProgressUpdated(progressState))
}
