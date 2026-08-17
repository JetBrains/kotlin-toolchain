/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.engine

import org.jetbrains.amper.cli.SoftTaskFailureException
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.cli.aggregate
import org.jetbrains.amper.engine.TaskExecutor.TaskExecutionFailed
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.stdlib.collections.partitionIsInstance
import org.jetbrains.amper.tasks.TaskResult

/**
 * Runs the given set of [tasks] and their dependencies, and throws an exception if any task fails.
 * If all tasks are successful, the results of all tasks that were executed are returned as a map, including results
 * of the task dependencies.
 *
 * Use the [mode][TaskExecutor.mode] on this [TaskExecutor] to choose whether to fail fast or keep executing as many
 * tasks as possible in case of failure.
 *
 * @throws TaskExecutionFailed if any task fails with a non-[UserReadableError] exception.
 * @throws UserReadableError if any of the given [tasks] is not found in the current task graph, or if a task
 * fails with a [UserReadableError].
 */
suspend fun TaskExecutor.runTasksAndReportOnFailure(tasks: Set<TaskId>): Map<TaskId, TaskResult> {
    val result = run(tasks) // this directly throws in fail-fast mode
    return result.resultsOrThrowCombinedError()
}

/**
 * Returns a map of successful task results, or throws an exception if any task failed. In case of failure, the first
 * task exception is rethrown, and all other task failures are provided as suppressed exceptions.
 */
private fun Map<TaskId, ExecutionResult>.resultsOrThrowCombinedError(): Map<TaskId, TaskResult> {
    val exceptions = values.filterIsInstance<ExecutionResult.Failure>().map { it.exception }
    val [softExceptions, otherExceptions] = exceptions.partitionIsInstance<_, SoftTaskFailureException>()
    val resultExceptions = softExceptions.aggregate() + otherExceptions

    if (resultExceptions.isNotEmpty()) {
        val firstException = resultExceptions.first()
        resultExceptions.drop(1).forEach { e -> firstException.addSuppressed(e) }
        throw firstException
    }
    return mapValues { [_, result] ->
        (result as? ExecutionResult.Success)?.result
            ?: error("All tasks should be successful here, because we throw in case of failure")
    }
}

/**
 * Utility property to get [TaskName.id] from a [Task] object.
 */
val Task.id
    get() = taskName.id
