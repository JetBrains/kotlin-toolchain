/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.processes

import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.amper.processes.output.ProcessOutputListener

class LineAwaitingProcessOutputListener(
    private val outputLineToWaitFor: (String) -> Boolean
) : ProcessOutputListener {
    private val waitedLine = CompletableDeferred<String>()

    override fun onStdoutLine(line: String, pid: Long) {
        if (!waitedLine.isCompleted && outputLineToWaitFor(line)) {
            waitedLine.complete(line)
        }
    }

    override fun onStderrLine(line: String, pid: Long) {
        // do nothing
    }

    suspend fun awaitLine() = waitedLine.await()
}