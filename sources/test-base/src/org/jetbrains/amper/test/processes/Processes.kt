/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.processes

import org.jetbrains.amper.processes.ProcessResult

/**
 * Throws an [IllegalStateException] (with detailed stderr or merged output) if this result's
 * [exitCode][ProcessResult.exitCode] is non-zero.
 *
 * This is not an assertion utility, but rather a safeguard for side processes launched as part of tests.
 */
fun <T : ProcessResult> T.checkExitCodeIsZero(): T {
    check(exitCode == 0) {
        buildString {
            append("Execution failed with exit code $exitCode for command: $command")
            when (this@checkExitCodeIsZero) {
                is ProcessResult.WithOutputs -> {
                    appendLine()
                    append("Process stderr:\n${stderr}")
                }
                is ProcessResult.WithMergedOutputs -> {
                    appendLine()
                    append("Process output (merged stdout+stderr):\n${stdoutAndStderr}")
                }
            }
        }
    }
    return this
}
