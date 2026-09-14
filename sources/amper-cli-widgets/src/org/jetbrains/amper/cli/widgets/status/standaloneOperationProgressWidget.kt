/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.events.sink.NoopEventSink
import org.jetbrains.amper.events.sink.OperationEventSink

/**
 * Allows to have CLI progress reporting outside the task graph.
 */
suspend inline fun <R> standaloneOperationProgressWidget(
    terminal: Terminal,
    crossinline block: suspend context(OperationEventSink) () -> R,
): R {
    return if (terminal.terminalInfo.outputInteractive) {
        coroutineScope {
            try {
                context(OperationProgressWidgetSink(terminal, this)) {
                    block()
                }
            } finally {
                // Need to cancel all the widget jobs so it doesn't hang.
                coroutineContext.cancelChildren()
            }
        }
    } else {
        context(NoopEventSink) {
            // TODO: Provide some progress reporting for non-interactive sessions
            block()
        }
    }
}
