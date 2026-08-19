/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.events

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.amper.cli.widgets.status.TaskProgressWidgetSink
import org.jetbrains.amper.events.sink.GlobalEventSink
import org.jetbrains.amper.events.sink.NoopEventSink

/**
 * Sets up an animated progress-reporting widget if necessary.
 */
class TaskProgressWidgetEventSinkContributor(
    private val terminal: Terminal,
    private val coroutineScope: CoroutineScope,
) : EventSinkContributor {
    override fun createGlobalSink(): GlobalEventSink {
        if (!terminal.terminalInfo.outputInteractive)
            return NoopEventSink

        return TaskProgressWidgetSink(
            terminal = terminal,
            coroutineScope = coroutineScope,
        )
    }
}
