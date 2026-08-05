/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters.DependencyBuildProblem
import org.jetbrains.amper.frontend.dr.resolver.diagnostics.reporters.ModuleDependencyWithOverriddenVersion
import org.jetbrains.amper.problems.reporting.BuildProblem
import org.jetbrains.amper.problems.reporting.BuildProblemImpl
import org.jetbrains.amper.problems.reporting.BuildProblemSource
import org.jetbrains.amper.problems.reporting.DiagnosticId
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import java.util.concurrent.ConcurrentHashMap

/**
 * Skips reporting of some problems that were already reported via this.
 *
 * Only certain problem types which are known to be safe to skip (and need to be skipped) can be skipped.
 */
class DeduplicatingProblemReporter(
    val delegate: ProblemReporter,
) : ProblemReporter {
    private val alreadyReported: MutableSet<ProblemId> = ConcurrentHashMap.newKeySet()

    override fun reportMessage(message: BuildProblem) {
        when (message) {
            // List of problem types that need to be deduplicated
            is BuildProblemImpl,
            is DependencyBuildProblem,
            is ModuleDependencyWithOverriddenVersion
                -> {
                if (!alreadyReported.add(message.toId())) {
                    return
                }
            }
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
