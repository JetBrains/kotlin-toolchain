/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.problems.reporting.plus
import java.nio.file.Path

/**
 * Creates a [ProblemReporter] implementation to be used in CLI.
 */
fun createProblemReporterForCli(
    terminal: Terminal,
    projectRoot: Path?,
): ProblemReporter = DeduplicatingProblemReporter(
    delegate = RichTerminalProblemReporter(terminal, projectRoot) +
            LoggingProblemReporter(),
)
