/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes.pipe

import kotlinx.coroutines.channels.Channel
import org.jetbrains.amper.processes.ProcessInput
import org.jetbrains.amper.processes.ProcessResult
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream

/**
 * A pipe that connects the standard output of a process to the input of another process.
 *
 * The [ProcessPipe] instance must be used as [ProcessInput] of the process receiving the data (the "Receiver"), and as
 * [ProcessOutputMode] of the process producing the data (called the "Producer" below).
 *
 * When the _Producer_ terminates, the stdin stream of the _Receiver_ is closed.
 *
 * If the _Receiver_ terminates earlier than the _Producer, or if other input error occurs, the remaining output of
 * the _Producer_ is discarded.
 */
class ProcessPipe(
    /**
     * Whether the standard error stream of the producer process should also be piped to standard input of the next
     * process, so both stdout and stderr streams are merged together before piping them.
     */
    includeStderr: Boolean,
    /**
     * A listener that sees everything going through the pipe via the stdout-related callbacks.
     *
     * Nothing goes to the stderr-related callbacks, because either stderr is not piped at all
     * (includeStderr `== false`), or it is piped via stdout (`includeStderr == true`).
     */
    eavesDroppingListener: ProcessOutputListener = ProcessOutputListener.NOOP,
) : ProcessInput.Stream, ProcessOutputMode.Listen<ProcessResult> {

    override val redirectStderrToStdout: Boolean = includeStderr

    private val outputLinesChannel = Channel<String>(capacity = Channel.UNLIMITED)

    override val listener = eavesDroppingListener + object : ProcessOutputListener {
        override fun onStdoutLine(line: String, pid: Long) {
            outputLinesChannel.trySend(line)
        }
        override fun onStderrLine(line: String, pid: Long) = Unit

        override fun onStreamsFlushed(exitCode: Int, pid: Long) {
            outputLinesChannel.close()
        }
    }

    override fun refineResult(baseResult: ProcessResult): ProcessResult = baseResult

    override suspend fun writeTo(processStdin: OutputStream) {
        try {
            PrintStream(processStdin, true, Charsets.UTF_8).use { stream ->
                for (line in outputLinesChannel) {
                    stream.println(line)
                }
            }
        } catch (_: IOException) {
            // broken pipe - just abort
            outputLinesChannel.close()
        }
    }
}