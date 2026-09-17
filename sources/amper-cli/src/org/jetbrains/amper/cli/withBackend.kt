/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.events.createProgressStatusWidgetSink
import org.jetbrains.amper.cli.events.createTestRenderingSink
import org.jetbrains.amper.engine.TaskExecutor
import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.Event
import org.jetbrains.amper.events.GlobalScopedEvent
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.events.sink.GlobalEventSink
import org.jetbrains.amper.events.sink.plus
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.tasks.CinteropGenSettings
import org.jetbrains.amper.tasks.ios.XcodeBuildSettingsResolution
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal suspend fun <T> withBackend(
    cliContext: ProjectCliContext,
    model: Model,
    runSettings: AllRunSettings = AllRunSettings(),
    cinteropGenSettings: CinteropGenSettings = CinteropGenSettings(),
    includePluginTasks: Boolean = true,
    xcodeBuildSettingsResolution: XcodeBuildSettingsResolution = XcodeBuildSettingsResolution,
    taskExecutionMode: TaskExecutor.Mode = TaskExecutor.Mode.FAIL_FAST,
    block: suspend (AmperBackend) -> T,
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        returnsResultOf(block)
    }

    return coroutineScope {
        val backgroundScope = childScope("project background scope")

        try {
            withBackend(
                cliContext = cliContext,
                model = model,
                globalEventSink = createProgressStatusWidgetSink(
                    terminal = cliContext.terminal,
                    coroutineScope = backgroundScope,
                ),
                runSettings = runSettings,
                cinteropGenSettings = cinteropGenSettings,
                includePluginTasks = includePluginTasks,
                xcodeBuildSettingsResolution = xcodeBuildSettingsResolution,
                taskExecutionMode = taskExecutionMode,
                block = block,
            )
        } finally {
            spanBuilder("Await background scope completion").use {
                // backgroundScope.cancel() would be sufficient because coroutineScope{} would wait,
                // but by waiting here we can measure the waiting time with telemetry
                backgroundScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }
}

internal suspend fun <T> withBackend(
    cliContext: ProjectCliContext,
    model: Model,
    globalEventSink: GlobalEventSink,
    runSettings: AllRunSettings = AllRunSettings(),
    cinteropGenSettings: CinteropGenSettings = CinteropGenSettings(),
    includePluginTasks: Boolean = true,
    xcodeBuildSettingsResolution: XcodeBuildSettingsResolution = XcodeBuildSettingsResolution,
    taskExecutionMode: TaskExecutor.Mode = TaskExecutor.Mode.FAIL_FAST,
    block: suspend (AmperBackend) -> T,
): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        returnsResultOf(block)
    }

    // TODO think of a better place to activate it. e.g. we need it in tests too
    // TODO disabled jul bridge for now since it reports too much in debug mode
    //  and does not handle source class names from jul LogRecord
    // JulTinylogBridge.activate()

    val backend = AmperBackend(
        context = cliContext,
        model = model,
        runSettings = runSettings,
        cinteropGenSettings = cinteropGenSettings,
        includePluginTasks = includePluginTasks,
        xcodeBuildSettingsResolution = xcodeBuildSettingsResolution,
        taskExecutionMode = taskExecutionMode,
        globalEventSink = globalEventSink + DomainEventFlatteningSink(
            sink = createTestRenderingSink(
                terminal = cliContext.terminal,
                testRunSettings = runSettings,
            ),
        ),
    )
    return spanBuilder("Run command with backend").use {
        block(backend)
    }
}

private class DomainEventFlatteningSink(
    val sink: EventSink<OperationScopedEvent.DomainEvent>,
) : EventSink<Event> {
    override fun emit(event: Event) {
        when (event) {
            is GlobalScopedEvent.BuildEvent -> emit(event.event)
            is BuildScopedEvent.TaskEvent -> emit(event.event)
            is OperationScopedEvent.ChildOperationEvent -> emit(event.event)
            is OperationScopedEvent.DomainEvent -> sink.emit(event)
            else -> Unit  // Not interesting
        }
    }
}

internal fun CoroutineScope.childScope(name: String): CoroutineScope =
    CoroutineScope(coroutineContext + SupervisorJob(parent = coroutineContext.job) + CoroutineName(name))
