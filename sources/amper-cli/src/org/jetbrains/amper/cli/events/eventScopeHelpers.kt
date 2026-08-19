/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.events

import org.jetbrains.amper.events.BuildId
import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.GlobalScopedEvent
import org.jetbrains.amper.events.OperationId
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.TaskExecutionId
import org.jetbrains.amper.events.payload.TaskMonikerSpec
import org.jetbrains.amper.events.sink.BuildEventSink
import org.jetbrains.amper.events.sink.GlobalEventSink
import org.jetbrains.amper.events.sink.OperationEventSink
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

context(sink: GlobalEventSink)
inline fun <R> buildEventScope(
    totalTaskCount: Int,
    block: context(BuildEventSink) () -> R,
): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val newId = BuildId()
    sink.emit(
        GlobalScopedEvent.BuildStarted(
            id = newId,
            totalTasksCount = totalTaskCount,
        )
    )
    val nestedSink = BuildEventSink(sink, newId)
    return try {
        block(nestedSink)
    } finally {
        sink.emit(GlobalScopedEvent.BuildFinished(newId))
    }
}

context(sink: BuildEventSink)
inline fun <R> taskEventScope(
    moniker: TaskMonikerSpec,
    isInteractive: Boolean,
    block: context(OperationEventSink) () -> R,
): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val newId = TaskExecutionId()
    sink.emit(BuildScopedEvent.TaskStarted(newId, moniker, isInteractive))
    val nestedSink = OperationEventSink(sink, newId)
    return try {
        block(nestedSink)
    } finally {
        sink.emit(BuildScopedEvent.TaskFinished(newId))
    }
}

context(sink: OperationEventSink)
inline fun <R> operationEventScope(
    moniker: String,
    block: context(OperationEventSink) () -> R,
): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    val newId = OperationId()
    sink.emit(OperationScopedEvent.Started(newId, moniker))
    val nestedSink = OperationEventSink(sink, newId)
    return try {
        block(nestedSink)
    } finally {
        sink.emit(OperationScopedEvent.Finished(newId))
    }
}
