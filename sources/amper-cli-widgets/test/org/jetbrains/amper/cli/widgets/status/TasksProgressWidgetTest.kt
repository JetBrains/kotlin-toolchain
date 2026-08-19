/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.cli.widgets.status

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import org.jetbrains.amper.events.BuildId
import org.jetbrains.amper.events.TaskExecutionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

class TasksProgressWidgetTest {

    /**
     * Renders the widget for the given [state] in a terminal of the given [height]
     * and returns only the lines that describe the task/operation hierarchy.
     */
    private fun renderOperationLines(height: Int, state: BuildState): List<String> {
        val terminal = Terminal(
            terminalInterface = TerminalRecorder(
                ansiLevel = AnsiLevel.NONE,
                width = 80,
                height = height,
            ),
            interactive = true,
        )
        val rendered = terminal.render(state.render(terminal = terminal))
        return rendered.lines()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() }
            .dropWhile { it.startsWith("[") || it.startsWith("Tests: ") }
    }

    private fun buildState(
        tasks: List<StatusEntryState>,
        totalTasksCount: Int = 10,
        completeTasksCount: Int = 0,
        testStatistics: TestStatistics = TestStatistics(),
    ) = BuildState(
        buildId = BuildId(),
        totalTasksCount = totalTasksCount,
        completeTasksCount = completeTasksCount,
        testStatistics = testStatistics,
        taskStates = tasks
            .mapIndexed { i, state -> TaskExecutionId() to state }
            .toMap(mutableMapOf()),
    )

    private fun entry(
        moniker: String,
        children: List<StatusEntryState> = [],
    ) = StatusEntryState(
        renderedMoniker = moniker,
        startTime = TimeSource.Monotonic.markNow(),
        shown = true,
        childEntries = children
            .mapIndexed { i, state -> EntryId(i) to state }
            .toMap(mutableMapOf()),
    )

    @Test
    fun `flat tasks exceeding budget`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 3
            height = 9,
            state = buildState((1..5).map { entry("Task $it") }),
        )

        assertEquals(
            [
                "→ Task 1",
                "→ Task 2",
                "→ Task 3",
                "(+2 more)",
            ],
            lines,
        )
    }

    @Test
    fun `single task with nested operations exceeding budget`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 3
            height = 9,
            state = buildState([entry("Task 1", children = (1..5).map { entry("Op $it") })]),
        )

        assertEquals(
            [
                "→ Task 1",
                "  ├─ ⠋ Op 1",
                "  ├─ ⠋ Op 2",
                "  ╰─ (+3 more)",
            ],
            lines,
        )
    }

    @Test
    fun `multi level hierarchy exceeding budget by one`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 3
            height = 9,
            state = buildState([
                entry("Task 1", children = [
                    entry("Op 1", children = [entry("SubOp 1"), entry("SubOp 2")]),
                ]),
                entry("Task 2"),
            ]),
        )

        assertEquals(
            [
                "→ Task 1",
                "  ╰─ Op 1",
                "     ├─ ⠋ SubOp 1",
                "     ╰─ ⠋ SubOp 2",
                "→ Task 2",
            ],
            lines,
        )
    }

    @Test
    fun `multi level hierarchy exceeding budget by two`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 3
            height = 9,
            state = buildState([
                entry("Task 1", children = [
                    entry("Op 1", children = [entry("SubOp 1"), entry("SubOp 2"), entry("SubOp 3")]),
                ]),
                entry("Task 2"),
                entry("Task 3"),
            ]),
        )

        assertEquals(
            [
                "→ Task 1",
                "  ╰─ Op 1",
                "     ├─ ⠋ SubOp 1",
                "     ╰─ (+2 more)",
                "(+2 more)",
            ],
            lines,
        )
    }

    @Test
    fun `budget exhausted before nested operations`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 1
            height = 3,
            state = buildState([
                entry("Task 1", children = (1..3).map { entry("Op $it") }),
                entry("Task 2"),
            ]),
        )

        assertEquals(
            [
                "→ Task 1",
                "  ╰─ (+3 more)",
                "→ Task 2",
            ],
            lines,
        )
    }

    @Test
    fun `total operations within budget`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 5
            height = 15,
            state = buildState([
                entry("Task 1", children = [entry("Op 1"), entry("Op 2")]),
                entry("Task 2"),
            ]),
        )

        assertEquals(
            [
                "→ Task 1",
                "  ├─ ⠋ Op 1",
                "  ╰─ ⠋ Op 2",
                "→ Task 2",
            ],
            lines,
        )
    }

    @Test
    fun `multiple nested branches cut off`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 2
            height = 6,
            state = buildState([
                entry("Task 1", children = [
                    entry("Op 1", children = [entry("SubOp 1"), entry("SubOp 2")]),
                    entry("Op 2"),
                ]),
            ]),
        )

        assertEquals(
            [
                "→ Task 1",
                "  ├─ Op 1",
                "  │  ╰─ (+2 more)",
                "  ╰─ ⠋ Op 2",
            ],
            lines,
        )
    }

    @Test
    fun `hidden tasks are not rendered`() {
        val lines = renderOperationLines(  // maxTasksOnScreen = 3
            height = 9,
            state = buildState([
                entry("Task 1"),
                StatusEntryState(
                    renderedMoniker = "Task 2",
                    startTime = TimeSource.Monotonic.markNow(),
                    shown = false,
                ),
            ]),
        )

        assertEquals(["→ Task 1"], lines)
    }
}
