/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.problems.reporting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.amper.concurrency.mapConcurrently

/**
 * A [ProblemReporter] that collects problems so they can be queried later.
 *
 * Note: This class is not thread-safe.
 */
class CollectingProblemReporter : ProblemReporter {
    /**
     * All problems recorded so far.
     */
    val problems: List<BuildProblem>
        field = mutableListOf<BuildProblem>()

    override fun reportMessage(message: BuildProblem) {
        problems.add(message)
    }
}

/**
 * `true` if any problems with the [Level.Error] severity have been recorded so far;
 * `false` otherwise.
 */
val CollectingProblemReporter.anyErrorsReported get() = problems.any { it.level == Level.Error }

/**
 * `true` if any diagnositcs have been recorded so far;
 * `false` otherwise.
 */
val CollectingProblemReporter.anyProblemsReported get() = problems.isNotEmpty()

/**
 * `false` if any diagnositcs have been recorded so far;
 * `true` otherwise.
 */
val CollectingProblemReporter.noProblemsReported get() = problems.isEmpty()

/**
 * Report all collected problems from the current reporter to the given [other] reporter.
 */
fun CollectingProblemReporter.replayProblemsTo(other: ProblemReporter) =
    problems.forEach { other.reportMessage(it) }

/**
 * Maps the elements of this list concurrently on [Dispatchers.Default].
 *
 * Each element is processed with a dedicated [CollectingProblemReporter] passed to the [transform], so the parallel
 * computations don't need to synchronize on the [problemReporter], which is not required to be thread-safe.
 *
 * After all the elements are processed, the collected problems are replayed to the [problemReporter] in the
 * elements' order, so the problems are reported exactly as if the elements were processed sequentially.
 */
context(problemReporter: ProblemReporter)
suspend fun <T, R> List<T>.mapConcurrentlyPreservingProblemOrder(
    transform: suspend (T, CollectingProblemReporter) -> R,
): List<R> {
    val problemReporters = List(size) { CollectingProblemReporter() }
    val results = withContext(Dispatchers.Default) {
        zip(problemReporters).mapConcurrently { [element, elementReporter] ->
            transform(element, elementReporter)
        }
    }
    problemReporters.forEach { it.replayProblemsTo(problemReporter) }
    return results
}
