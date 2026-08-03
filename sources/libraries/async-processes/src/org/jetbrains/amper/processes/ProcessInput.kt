/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

import java.io.OutputStream
import java.nio.file.Path

sealed interface ProcessInput {

    /**
     * The child process inherits the standard input from the current process.
     *
     * Warning: `System.setIn()` doesn't change the input to be inherited.
     */
    data object Inherit : ProcessInput

    /**
     * The input will be read from the given file.
     */
    data class File(val path: Path) : ProcessInput

    /**
     * The input will be written to the stdin of the process via [writeTo].
     */
    fun interface Stream : ProcessInput {

        /**
         * Writes the input to the process's standard input stream (stdin).
         *
         * The stream doesn't need to be closed here, as it is closed automatically when this function completes.
         */
        suspend fun writeTo(processStdin: OutputStream)
    }

    companion object {

        /**
         * An empty input. The standard input stream is immediately closed.
         * This is a simple way to remove input interactivity, which is useful in tests.
         */
        val Empty: ProcessInput = Stream {}

        /**
         * A static UTF-8 text input.
         */
        fun text(input: String): ProcessInput = Stream { stdin ->
            stdin.write(input.encodeToByteArray())
        }
    }
}
