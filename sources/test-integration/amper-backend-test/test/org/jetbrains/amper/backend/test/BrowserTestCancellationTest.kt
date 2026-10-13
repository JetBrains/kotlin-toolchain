/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.backend.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.amper.cli.AmperBackend
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
import org.jetbrains.amper.testevents.TestStarted
import org.junit.jupiter.api.Tag
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@Tag("slow")
class BrowserTestCancellationTest : AmperIntegrationTestBase() {
    @Test
    fun `cancelling a running browser test promptly writes its partial report`() = runTestRespectingDelays(timeout = 3.minutes) {
        val context = setupTestProject(Dirs.amperTestProjectsRoot / "wasm-js-test-reports", copyToTemp = true)
        val problems = CollectingProblemReporter()
        val model = with(problems) {
            context.projectContext.readProjectModel(pluginData = [], mavenPluginXmls = [])
        }
        assertFalse(problems.anyProblemsReported)
        val started = CompletableDeferred<Unit>()
        val backend = AmperBackend(
            context = context,
            model = model,
            runSettings = AllRunSettings(testFilters = [TestFilter.includeTest("example.TimeoutTest.neverCompletes")]),
            globalEventSink = object : EventSink<GlobalScopedEvent> {
                override fun emit(event: GlobalScopedEvent) {
                    if (event.isPendingTest()) started.complete(Unit)
                }
            },
        )
        val run = launch(Dispatchers.Default) {
            backend.runTask(TaskId(":wasm-js-test-reports:testWasmJsDebug"))
        }
        try {
            withTimeout(2.minutes) { started.await() }
            val cancellationTime = measureTime { run.cancelAndJoin() }
            assertTrue(cancellationTime < 10.seconds, "Cancellation took $cancellationTime; it must not wait for the browser timeout")
        } finally {
            run.cancelAndJoin()
        }

        val report = context.buildOutputRoot.path / "reports" / "wasm-js-test-reports" / "wasmJs" / "debug" / "TEST-browser.xml"
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report.toFile())
        assertEquals("1", xml.documentElement.getAttribute("errors"))
        val error = xml.getElementsByTagName("error").item(0)
        assertEquals("neverCompletes", error.parentNode.attributes.getNamedItem("name").nodeValue)
        assertContains(error.textContent, "CancellationException")
    }
}

private fun Event.isPendingTest(): Boolean = when (this) {
    is GlobalScopedEvent.BuildEvent -> event.isPendingTest()
    is BuildScopedEvent.TaskEvent -> event.isPendingTest()
    is OperationScopedEvent.ChildOperationEvent -> event.isPendingTest()
    is TestStarted -> descriptor.displayName == "neverCompletes"
    else -> false
}
