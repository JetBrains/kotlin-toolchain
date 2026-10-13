/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.backend.test

import org.jetbrains.amper.cli.AmperBackend
import org.jetbrains.amper.cli.UserReadableError
import org.jetbrains.amper.events.BuildScopedEvent
import org.jetbrains.amper.events.Event
import org.jetbrains.amper.events.GlobalScopedEvent
import org.jetbrains.amper.events.OperationScopedEvent
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.frontend.TaskId
import org.jetbrains.amper.frontend.aomBuilder.readProjectModel
import org.jetbrains.amper.problems.reporting.CollectingProblemReporter
import org.jetbrains.amper.problems.reporting.anyProblemsReported
import org.jetbrains.amper.tasks.AllRunSettings
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.TestFilter
import org.jetbrains.amper.test.runTestRespectingDelays
import org.jetbrains.amper.testevents.TestFinished
import org.junit.jupiter.api.Tag
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

@Tag("slow")
class BrowserTestReportFailureTest : AmperIntegrationTestBase() {
    @Test
    fun `report writing failure does not replace a browser assertion failure`() = runTestRespectingDelays(timeout = 3.minutes) {
        val context = setupTestProject(Dirs.amperTestProjectsRoot / "wasm-js-test-reports", copyToTemp = true)
        val problems = CollectingProblemReporter()
        val model = with(problems) {
            context.projectContext.readProjectModel(pluginData = [], mavenPluginXmls = [])
        }
        assertFalse(problems.anyProblemsReported)
        val report = context.buildOutputRoot.path / "reports" / "wasm-js-test-reports" / "wasmJs" / "debug" / "TEST-browser.xml"
        val backend = AmperBackend(
            context = context,
            model = model,
            runSettings = AllRunSettings(testFilters = [TestFilter.includeTest("example.ReportingTest.synchronousFailure")]),
            globalEventSink = object : EventSink<GlobalScopedEvent> {
                override fun emit(event: GlobalScopedEvent) {
                    if (event.isFailedTest()) {
                        // Block report replacement after tests have started, independently of OS file permissions.
                        report.createDirectories()
                        (report / "blocker").writeText("The report target is a non-empty directory")
                    }
                }
            },
        )

        val failure = assertFailsWith<UserReadableError> {
            backend.runTask(TaskId(":wasm-js-test-reports:testWasmJsDebug"))
        }
        assertContains(failure.message, "tests failed for module 'wasm-js-test-reports'")
    }
}

private fun Event.isFailedTest(): Boolean = when (this) {
    is GlobalScopedEvent.BuildEvent -> event.isFailedTest()
    is BuildScopedEvent.TaskEvent -> event.isFailedTest()
    is OperationScopedEvent.ChildOperationEvent -> event.isFailedTest()
    is TestFinished.Failed -> true
    else -> false
}
