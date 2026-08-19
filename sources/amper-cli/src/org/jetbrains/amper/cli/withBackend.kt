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
import org.jetbrains.amper.cli.events.TaskProgressWidgetEventSinkContributor
import org.jetbrains.amper.cli.events.TestEventSinksContributor
import org.jetbrains.amper.engine.TaskExecutor
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

    // TODO think of a better place to activate it. e.g. we need it in tests too
    // TODO disabled jul bridge for now since it reports too much in debug mode
    //  and does not handle source class names from jul LogRecord
    // JulTinylogBridge.activate()

    return coroutineScope {
        val backgroundScope = childScope("project background scope")

        val eventSinkContributors = [
            TaskProgressWidgetEventSinkContributor(
                terminal = cliContext.terminal,
                coroutineScope = backgroundScope,
            ),
            TestEventSinksContributor(
                terminal = cliContext.terminal,
                testRunSettings = runSettings,
            ),
        ]

        val backend = AmperBackend(
            context = cliContext,
            model = model,
            runSettings = runSettings,
            cinteropGenSettings = cinteropGenSettings,
            includePluginTasks = includePluginTasks,
            xcodeBuildSettingsResolution = xcodeBuildSettingsResolution,
            taskExecutionMode = taskExecutionMode,
            eventSinkContributors = eventSinkContributors,
        )
        try {
            spanBuilder("Run command with backend").use {
                block(backend)
            }
        } finally {
            spanBuilder("Await background scope completion").use {
                // backgroundScope.cancel() would be sufficient because coroutineScope{} would wait,
                // but by waiting here we can measure the waiting time with telemetry
                backgroundScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }
}

private fun CoroutineScope.childScope(name: String): CoroutineScope =
    CoroutineScope(coroutineContext + SupervisorJob(parent = coroutineContext.job) + CoroutineName(name))
