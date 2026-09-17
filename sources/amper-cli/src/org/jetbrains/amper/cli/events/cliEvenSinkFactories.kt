/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.events

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.amper.cli.widgets.status.ProgressWidgetSink
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.events.sink.GlobalEventSink
import org.jetbrains.amper.events.sink.NoopEventSink
import org.jetbrains.amper.tasks.TestResultsFormat
import org.jetbrains.amper.tasks.TestRunSettings
import org.jetbrains.amper.test.PrettyRenderer
import org.jetbrains.amper.test.TeamCityRenderer
import org.jetbrains.amper.testevents.TestEvent

/**
 * Sets up an animated progress-reporting widget if necessary.
 */
fun createProgressStatusWidgetSink(
    terminal: Terminal,
    coroutineScope: CoroutineScope,
): GlobalEventSink {
    if (!terminal.terminalInfo.outputInteractive)
        return NoopEventSink

    return ProgressWidgetSink(
        terminal = terminal,
        coroutineScope = coroutineScope,
    )
}

/**
 * Sets up [TestEvent] rendering.
 */
fun createTestRenderingSink(
    terminal: Terminal,
    testRunSettings: TestRunSettings,
): EventSink<OperationScopedEvent.DomainEvent> {
    // Consumes test events and prints them if needed
    val testRenderer = when (testRunSettings.testResultsFormat) {
        TestResultsFormat.Pretty -> PrettyRenderer(terminal)
        TestResultsFormat.TeamCity -> TeamCityRenderer(terminal)
    }

    return object : EventSink<OperationScopedEvent.DomainEvent> {
        override fun emit(event: OperationScopedEvent.DomainEvent) {
            if (event is TestEvent) testRenderer.emit(event)
        }
    }
}
