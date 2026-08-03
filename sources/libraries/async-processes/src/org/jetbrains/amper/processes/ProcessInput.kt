/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

import kotlinx.coroutines.channels.Channel
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.processes.output.ProcessOutputMode
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream

sealed interface ProcessInput {

    /**
     * The child process inherits the standard input from the current process.
     *
     * Warning: `System.setIn()` doesn't change the input to be inherited.
     */
    data object Inherit : ProcessInput {
        override suspend fun writeTo(processStdin: OutputStream) = Unit // do not close the stream here
    }

    /**
     * Nothing is sent to the standard input of the child process, the stream is immediately closed.
     */
    data object Empty : ProcessInput {
        override suspend fun writeTo(processStdin: OutputStream) {
            // We just close the input by default.
            processStdin.close()
        }
    }

    /**
     * The given [input] text is written as UTF-8 in a single write operation to the child process' standard input.
     */
    data class Text(val input: String) : ProcessInput {
        override suspend fun writeTo(processStdin: OutputStream) = processStdin.use {
            it.write(input.encodeToByteArray())
        }
    }

    /**
     * The standard input of the process receives data from the output of another process.
     *
     * The [Pipe] instance must be used as `input` of the process receiving the data (the "Receiver"), and as
     * [ProcessOutputMode] of the process producing the data (called the "Producer" below).
     *
     * When the _Producer_ terminates, the stdin stream of the _Receiver_ is closed.
     *
     * If the _Receiver_ terminates earlier than the _Producer, or if other input error occurs, the remaining output of
     * the _Producer_ is discarded.
     */
    class Pipe(
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
    ) : ProcessInput, ProcessOutputMode.Listen<ProcessResult> {

        override val redirectStderrToStdout: Boolean = includeStderr

        private val outputLinesChannel = Channel<String>(capacity = Channel.UNLIMITED)

        override val listener = eavesDroppingListener + object : ProcessOutputListener {
            override fun onStdoutLine(line: String, pid: Long) {
                outputLinesChannel.trySend(line)
            }
            override fun onStderrLine(line: String, pid: Long) = Unit

            override fun onProcessTerminated(exitCode: Int, pid: Long) {
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

    /**
     * Writes the input to the process's standard input stream (stdin).
     *
     * IMPORTANT: The implementor is responsible for closing the stream in the end.
     */
    suspend fun writeTo(processStdin: OutputStream)
}
