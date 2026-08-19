/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.engine.TaskExecutor.Mode
import org.jetbrains.amper.events.payload.TaskMonikerSpec
import org.jetbrains.amper.events.sink.NoopEventSink
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.problems.reporting.NoopProblemReporter
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TaskExecutorTest {

    @Test
    fun simpleTaskDependencies() = runTestWithMdc {
        val builder = TaskGraphBuilder()
        builder.registerTask(TestTask("A"), listOf(testTaskName("B")))
        builder.registerTask(TestTask("B"), listOf(testTaskName("C")))
        builder.registerTask(TestTask("C"), listOf(testTaskName("D")))
        builder.registerTask(TestTask("D"))
        val graph = builder.build()

        val executor = TaskExecutor(graph, TaskExecutor.Mode.GREEDY)

        executed.clear()
        executor.run(setOf(TaskId("A")))
        assertEquals(listOf("D", "C", "B", "A"), executed)

        executed.clear()
        executor.run(setOf(TaskId("B")))
        assertEquals(listOf("D", "C", "B"), executed)

        executed.clear()
        executor.run(setOf(TaskId("C")))
        assertEquals(listOf("D", "C"), executed)
    }

    @Test
    fun diamondTaskDependencies() = runTestWithMdc {
        val builder = TaskGraphBuilder()
        builder.registerTask(TestTask("A"), listOf(testTaskName("B"), testTaskName("C")))
        builder.registerTask(TestTask("B"), listOf(testTaskName("D")))
        builder.registerTask(TestTask("C"), listOf(testTaskName("D")))
        builder.registerTask(TestTask("D"))
        val graph = builder.build()

        val executor = TaskExecutor(graph, TaskExecutor.Mode.GREEDY)
        executor.run(setOf(TaskId("A")))

        if (executed != listOf("D", "B", "C", "A") && executed != listOf("D", "C", "B", "A")) {
            fail("Wrong execution order: $executed")
        }
    }

    @Test
    fun complexTaskDependencies() = runTestWithMdc {
        executed.clear()

        val builder = TaskGraphBuilder()
        builder.registerTask(TestTask("A"), dependsOn = listOf(testTaskName("B")))
        builder.registerTask(TestTask("B"), dependsOn = listOf(testTaskName("C"), testTaskName("E")))
        builder.registerTask(TestTask("C"), dependsOn = listOf(testTaskName("D"), testTaskName("E")))
        builder.registerTask(TestTask("D"), dependsOn = listOf(testTaskName("F")))
        builder.registerTask(TestTask("E"), dependsOn = listOf(testTaskName("F")))
        builder.registerTask(TestTask("F"))
        val graph = builder.build()

        val executor = TaskExecutor(graph, TaskExecutor.Mode.FAIL_FAST)
        executor.run(setOf(TaskId("A")))

        if (executed != listOf("F", "E", "D", "C", "B", "A") && executed != listOf("F", "D", "E", "C", "B", "A")) {
            fail("Wrong execution order: $executed")
        }
    }

    @Test
    fun failedTaskCancelsDependentChain() = runTestWithMdc {
        val builder = TaskGraphBuilder()
        builder.registerTask(TestTask("A"), listOf(testTaskName("B")))
        builder.registerTask(TestTask("B"), listOf(testTaskName("C")))
        builder.registerTask(TestTask("C") { error("test failure") }, listOf(testTaskName("D")))
        builder.registerTask(TestTask("D"))
        val graph = builder.build()

        val executor = TaskExecutor(graph, TaskExecutor.Mode.GREEDY)
        val result = executor.run(setOf(TaskId("A")))

        assertIs<ExecutionResult.Success>(result.getValue(TaskId("D")))

        val resultC = result.getValue(TaskId("C"))
        assertIs<ExecutionResult.Failure>(resultC)
        assertIs<IllegalStateException>(resultC.exception)

        val resultB = result.getValue(TaskId("B"))
        assertIs<ExecutionResult.DependencyFailed>(resultB)
        assertEquals(setOf(resultC), resultB.unsuccessfulDependencies)
        assertEquals(setOf(resultC), resultB.transitiveFailures)

        val resultA = result.getValue(TaskId("A"))
        assertIs<ExecutionResult.DependencyFailed>(resultA)
        assertEquals(setOf(resultB), resultA.unsuccessfulDependencies)
        assertEquals(setOf(resultC), resultA.transitiveFailures)
    }

    @Test
    fun executesAllPossibleTasksOnTaskFailureInGreedyMode() = runTestWithMdc {
        // Given the task graph dependencies:
        // A -> B
        // A -> C
        // C -> D
        // if D fails, B should be still executed

        val builder = TaskGraphBuilder()
        builder.registerTask(TestTask("A"), listOf(testTaskName("B"), testTaskName("C")))
        builder.registerTask(TestTask("B") { delay(500) }) // add enough time for D to cancel execution of itself
        builder.registerTask(TestTask("C"), listOf(testTaskName("D")))
        builder.registerTask(TestTask("D") { error("throw") })
        val graph = builder.build()

        val executor = TaskExecutor(graph, TaskExecutor.Mode.GREEDY)
        val result = executor.run(setOf(TaskId("A")))

        val resultD = result.getValue(TaskId("D"))
        assertIs<ExecutionResult.Failure>(resultD)

        val resultC = result.getValue(TaskId("C"))
        assertIs<ExecutionResult.DependencyFailed>(resultC)
        assertEquals(setOf(resultD), resultC.unsuccessfulDependencies)
        assertEquals(setOf(resultD), resultC.transitiveFailures)

        val resultB = result.getValue(TaskId("B"))
        assertIs<ExecutionResult.Success>(resultB)

        val resultA = result.getValue(TaskId("A"))
        assertIs<ExecutionResult.DependencyFailed>(resultA)
        assertEquals(setOf(resultC), resultA.unsuccessfulDependencies)
        assertEquals(setOf(resultD), resultA.transitiveFailures)
    }

    @Test
    fun stopsOnFirstTaskFailureInFailFastMode() = runTestWithMdc {
        // Given the task graph dependencies:
        // A -> B
        // A -> C
        // C -> D
        // if D fails, B should not be still executed because of FAIL_FAST mode

        val builder = TaskGraphBuilder()
        builder.registerTask(TestTask("A"), listOf(testTaskName("B"), testTaskName("C")))
        builder.registerTask(TestTask("B") { delay(500.milliseconds) }) // add enough time for D to cancel execution of itself
        builder.registerTask(TestTask("C"), listOf(testTaskName("D")))
        builder.registerTask(TestTask("D") { error("throw") })
        val graph = builder.build()
        val executor = TaskExecutor(graph, TaskExecutor.Mode.FAIL_FAST)
        val result = assertFailsWith(TaskExecutor.TaskExecutionFailed::class) {
            executor.run(setOf(TaskId("A")))
        }
        assertEquals("Task 'D' failed: java.lang.IllegalStateException: throw", result.message)
    }

    @Test
    fun failsOnTaskDependencyCycle() = runTestWithMdc {
        // Given the task graph dependencies:
        // D -> C
        // C -> B
        // B -> A
        // A -> D
        // it should fail

        val builder = TaskGraphBuilder()
        builder.registerTask(TestTask("D"), listOf(testTaskName("C")))
        builder.registerTask(TestTask("C"), listOf(testTaskName("B")))
        builder.registerTask(TestTask("B"), listOf(testTaskName("A")))
        builder.registerTask(TestTask("A"), listOf(testTaskName("D")))
        val error = assertThrows<UserReadableError> {
            builder.build()
        }

        val expectedError =  """
            Task dependency loop is detected:
            B
            ╰> A
               ╰> D
                  ╰> C
                     ╰> B
        """.trimIndent()
        assertEquals(expectedError, error.message)
    }

    @Test
    fun rootTasksExecuteInParallel() = runTestWithMdc {
        val builder = TaskGraphBuilder()
        val parallelTasks = setOf("A", "B", "C")
        parallelTasks.forEach { name ->
            builder.registerTask(TestTask(name) {
                withTimeout(10.seconds) {
                    while (maxParallelTasksCount.get() < parallelTasks.size) {
                        delay(10)
                    }
                }
            })
        }
        val graph = builder.build()
        val executor = TaskExecutor(graph, TaskExecutor.Mode.FAIL_FAST)
        executor.run(setOf(TaskId("A"), TaskId("B"), TaskId("C")))
        assertEquals(3, maxParallelTasksCount.get())
    }

    private val executed = mutableListOf<String>()
    private val runningTasksCount = AtomicInteger(0)
    private val maxParallelTasksCount = AtomicInteger(0)

    private fun testTaskName(name: String) = TaskName(TaskId(name), spec = TaskMonikerSpec.ProjectScoped(name))

    private inner class TestTask(
        val name: String,
        val body: suspend () -> Unit = {},
    ): Task {
        override val taskName: TaskName
            get() = testTaskName(name)

        context(executionContext: TaskGraphExecutionContext)
        override suspend fun run(
            dependenciesResult: List<TaskResult>,
        ): TaskResult {
            val currentTasksCount = runningTasksCount.incrementAndGet()
            maxParallelTasksCount.updateAndGet { max -> max(max, currentTasksCount) }
            try {
                synchronized(executed) {
                    executed.add(name)
                }
                body()
                return TestTaskResult(taskName)
            } finally {
                runningTasksCount.decrementAndGet()
            }
        }
    }

    private class TestTaskResult(val taskName: TaskName) : TaskResult

    private fun TaskExecutor(
        graph: TaskGraph,
        mode: Mode,
    ) = TaskExecutor(
        graph = graph,
        mode = mode,
        problemReporter = NoopProblemReporter,
        globalEventSink = NoopEventSink,
        eventSinkContributors = [],
    )
}
