/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.processes

import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.slf4j.Logger
import org.slf4j.event.Level

class LoggingProcessOutputListener(
    val logger: Logger,
    val prefix: String = "",
    val stdErrPrefix: String = prefix,
    val stdoutLoggingLevel: Level = Level.INFO,
    val stderrLoggingLevel: Level = Level.ERROR,
): ProcessOutputListener {

    override fun onStdoutLine(line: String, pid: Long) {
        logger.atLevel(stdoutLoggingLevel).log("$prefix$line")
    }

    override fun onStderrLine(line: String, pid: Long) {
        logger.atLevel(stderrLoggingLevel).log("$stdErrPrefix$line")
    }
}
