/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.cli.logging.withoutConsoleLogging
import org.jetbrains.amper.frontend.messages.renderMessage
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.slf4j.LoggerFactory

/**
 * A [ProblemReporter] that logs [renderMessage]-rendered problems using the logger implementations.
 * Never to the console.
 */
class LoggingProblemReporter : ProblemReporter {
    private val logger = LoggerFactory.getLogger("build")

    override fun reportMessage(message: BuildProblem) {
        val rendered = renderMessage(message)
        withoutConsoleLogging {
            when (message.level) {
                Level.Warning -> logger.warn(rendered)
                Level.Error -> logger.error(rendered)
                Level.WeakWarning -> logger.info(rendered)
            }
        }
    }
}