/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.engine

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.cli.events.EventSinkContributors
import org.jetbrains.amper.cli.events.buildEventScope
import org.jetbrains.amper.cli.events.createOperationSink
import org.jetbrains.amper.cli.events.taskEventScope
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.events.sink.BuildEventSink
import org.jetbrains.amper.events.sink.GlobalEventSink
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.events.sink.plus
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.stdlib.graphs.depthFirstNodeSequence
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.telemetry.spanBuilder
import org.jetbrains.amper.telemetry.use
import org.slf4j.MDC
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

class TaskExecutor(
    private val graph: TaskGraph,
    private val mode: Mode,
    private val problemReporter: ProblemReporter,
    private val globalEventSink: GlobalEventSink,
    private val eventSinkContributors: EventSinkContributors,
) {
    class ExecutionPlan(
        val totalTasksCount: Int,
    )

    private val availableProcessors = Runtime.getRuntime().availableProcessors()

    // TODO Should be configurable
    private val tasksDispatcher = Dispatchers.IO.limitedParallelism(availableProcessors.coerceAtLeast(3))

    /**
     * Runs the given set of tasks, and returns the result of all tasks that were executed, including dependencies.
     * Use the [mode] on this [TaskExecutor] to choose whether to fail fast or keep executing as many as as possible in
     * case of failure.
     *
     * @throws TaskExecutionFailed if any task fails with a non-[UserReadableError] exception in
     * [FAIL_FAST][Mode.FAIL_FAST] mode.
     * @throws UserReadableError if any of the given [tasksToRun] is not found in the current task graph, or if a task
     * fails with a [UserReadableError] in [FAIL_FAST][Mode.FAIL_FAST] mode.
     */
    // Dispatch on default dispatcher, execute on tasks dispatcher
    suspend fun run(tasksToRun: Set<TaskId>): Map<TaskId, ExecutionResult> = withContext(Dispatchers.Default) {
        require(tasksToRun.isNotEmpty()) { "tasksToRun cannot be empty" }
        spanBuilder("Run tasks")
            .setAttribute("root-tasks", tasksToRun.joinToString { it.value })
            .use {
                val executionPlan = buildExecutionPlan(tasksToRun)
                val hookManager = DefaultTaskGraphExecutionUtils()
                buildEventScope(
                    totalTaskCount = executionPlan.totalTasksCount,
                    sink = globalEventSink,
                ) {
                    try {
                        val results = ConcurrentHashMap<TaskId, Deferred<ExecutionResult>>()
                        val _ = context(hookManager) {
                            runTasks(tasksToRun, currentPath = emptyList(), results)
                        }

                        // this is just to unpack results (by that point, all tasks must have finished executing already)
                        results.mapValues { it.value.await() }
                    } finally {
                        spanBuilder("Post graph execution hooks").use {
                            hookManager.runPostGraphExecutionHooks()
                        }
                    }
                }
            }
    }

    private fun assertTaskIsKnown(taskId: TaskId) {
        if (taskId !in graph.nameToTask.keys) {
            val similarNames = findSimilarTaskNames(taskId).sorted()
            val extraInfo = if (similarNames.isEmpty()) "" else ", maybe you meant one of:\n${similarNames.joinToString("\n").prependIndent("   ")}"
            userReadableError("Task '${taskId.value}' was not found in the project$extraInfo")
        }
    }

    private fun findSimilarTaskNames(taskId: TaskId): List<String> =
        graph.nameToTask.keys
            .map { it.value }
            .filter { it.contains(taskId.value, ignoreCase = true) || taskId.value.contains(it, ignoreCase = true) }

    /**
     * Runs the tasks identified by [taskIds], and returns their results.
     * If one of the given tasks is already running, it will be awaited instead of starting a new coroutine.
     *
     * Fails if one of the given [taskIds] is already in the graph execution's [currentPath] (which means a cycle).
     */
    context(_: BuildEventSink, _: TaskGraphExecutionUtils)
    private suspend fun runTasks(
        taskIds: Set<TaskId>,
        currentPath: List<TaskId>,
        taskResults: ConcurrentMap<TaskId, Deferred<ExecutionResult>>,
    ): List<ExecutionResult> = coroutineScope {
        taskIds
            .map { taskId ->
                // NOTE: We don't need to check for cycles here, as the `TaskGraph` already ensures no cycles.
                taskResults.computeIfAbsent(taskId) {
                    async { runDependenciesAndTask(taskId, currentPath = currentPath, taskResults) }
                }
            }
            .awaitAll() // Note: we might be awaiting async coroutines from other scopes here
    }

    /**
     * Runs the given task's dependencies, and then the task itself.
     */
    context(_: BuildEventSink, _: TaskGraphExecutionUtils)
    private suspend fun runDependenciesAndTask(
        taskId: TaskId,
        currentPath: List<TaskId>,
        taskResults: ConcurrentMap<TaskId, Deferred<ExecutionResult>>,
    ): ExecutionResult {
        val taskDependencies = graph.dependencies[taskId] ?: emptySet()
        val dependencyResults = runTasks(taskDependencies, currentPath + taskId, taskResults)
        val (successful, unsuccessful) = dependencyResults.partitionDependencyResults()
        if (unsuccessful.isNotEmpty()) {
            // skip task execution since at least one dependency failed
            return ExecutionResult.DependencyFailed(taskId, unsuccessfulDependencies = unsuccessful)
        }
        return runSingleTaskSafely(taskId, successful)
    }

    private fun List<ExecutionResult>.partitionDependencyResults(): DependencyResults {
        // we don't use the stdlib's partition() here because we want to be type-safe and avoid casts
        val successful = mutableListOf<TaskResult>()
        val unsuccessful = mutableSetOf<ExecutionResult.Unsuccessful>()
        forEach {
            when (it) {
                is ExecutionResult.Success -> successful.add(it.result)
                is ExecutionResult.Unsuccessful -> unsuccessful.add(it)
            }
        }
        return DependencyResults(successful = successful, unsuccessful = unsuccessful)
    }

    private data class DependencyResults(
        val successful: List<TaskResult>,
        val unsuccessful: Set<ExecutionResult.Unsuccessful>,
    )

    /**
     * Runs the task identified by [taskId], and returns its result.
     */
    context(_: BuildEventSink, _: TaskGraphExecutionUtils)
    private suspend fun runSingleTaskSafely(
        taskId: TaskId,
        dependencyResults: List<TaskResult>,
    ): ExecutionResult = try {
        val taskResult = runSingleTask(taskId, dependencyResults)
        ExecutionResult.Success(taskId, taskResult)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive() // cooperate with cancellations
        when (mode) {
            Mode.GREEDY -> ExecutionResult.Failure(taskId, e)
            Mode.FAIL_FAST -> when (e) {
                is UserReadableError -> userReadableError("Task '${taskId.value}' failed: ${e.message}", exitCode = e.exitCode)
                else -> throw TaskExecutionFailed(taskId, e)
            }
        }
    }

    context(_: BuildEventSink, executionUtils: TaskGraphExecutionUtils)
    private suspend fun runSingleTask(
        taskId: TaskId,
        dependencyResults: List<TaskResult>,
    ): TaskResult = spanBuilder("task ${taskId.value}").use {
        val task = graph.nameToTask[taskId] ?: error("Unable to find task by name: ${taskId.value}")
        taskEventScope(
            moniker = task.taskName.spec,
            isInteractive = task is RunTask,
        ) {
            val mdcWithTaskName = MDCContext(MDC.getCopyOfContextMap() + ("amper-task-name" to taskId.value))
            withContext(tasksDispatcher + mdcWithTaskName + CoroutineName("task:${taskId.value}")) {
                task.run(
                    dependenciesResult = dependencyResults,
                    executionContext = TaskGraphExecutionContext(
                        // TODO: Introduce a scoped event-based problem reporter?
                        problemReporter = problemReporter,
                        eventSink = contextOf<OperationEventSink>() + eventSinkContributors.createOperationSink(),
                        executionUtils = executionUtils,
                    ),
                )
            }
        }
    }

    private fun buildExecutionPlan(
        initialTaskNames: Collection<TaskId>,
    ) = ExecutionPlan(
        totalTasksCount = depthFirstNodeSequence(
            roots = initialTaskNames,
            adjacent = { graph.dependencies[it] ?: emptyList() }
        ).onEach(::assertTaskIsKnown).count()
    )

    enum class Mode {
        /**
         * Upon task failure continue execution of all other tasks,
         * that are independent of the failed task in the task graph
         */
        GREEDY,

        /**
         * Fail on a first failed task, cancel all running and queued tasks upon failure
         */
        FAIL_FAST,
    }

    class TaskExecutionFailed(val taskId: TaskId, val exception: Throwable)
        : Exception("Task '${taskId.value}' failed: $exception", exception)
}
