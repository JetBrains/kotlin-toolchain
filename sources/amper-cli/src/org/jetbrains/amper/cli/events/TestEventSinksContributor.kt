/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.events

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.tasks.TestResultsFormat
import org.jetbrains.amper.tasks.TestRunSettings
import org.jetbrains.amper.test.PrettyRenderer
import org.jetbrains.amper.test.TeamCityRenderer
import org.jetbrains.amper.testevents.TestEvent

/**
 * Sets up [TestEvent] rendering.
 */
class TestEventSinksContributor(
    private val testRunSettings: TestRunSettings,
    private val terminal: Terminal,
) : EventSinkContributor {
    override fun createOperationSink(): OperationEventSink {
        // Consumes test events and prints them if needed
        val testRenderer = when (testRunSettings.testResultsFormat) {
            TestResultsFormat.Pretty -> PrettyRenderer(terminal)
            TestResultsFormat.TeamCity -> TeamCityRenderer(terminal)
        }

        return object : OperationEventSink {
            override fun emit(event: OperationScopedEvent) {
                if (event is TestEvent) testRenderer.emit(event)
            }
        }
    }
}
