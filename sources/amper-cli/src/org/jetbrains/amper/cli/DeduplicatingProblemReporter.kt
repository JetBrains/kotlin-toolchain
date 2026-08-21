/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import java.util.concurrent.ConcurrentHashMap

/**
 * Skips reporting of problems that were already reported via this.
 */
class DeduplicatingProblemReporter(
    val delegate: ProblemReporter,
) : ProblemReporter {
    private val alreadyReported: MutableSet<ProblemId> = ConcurrentHashMap.newKeySet()

    override fun reportMessage(message: BuildProblem) {
        if (!alreadyReported.add(message.toId())) {
            return
        }

        delegate.reportMessage(message)
    }

    private fun BuildProblem.toId() = ProblemId(source, message, level, diagnosticId)

    data class ProblemId(
        val source: BuildProblemSource,
        val message: String,
        val level: Level,
        val diagnosticId: DiagnosticId,
    )
}
