/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import jetbrains.buildServer.messages.serviceMessages.MessageWithAttributes
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes
import jetbrains.buildServer.messages.serviceMessages.TestFailed
import jetbrains.buildServer.messages.serviceMessages.TestFinished
import jetbrains.buildServer.messages.serviceMessages.TestIgnored
import jetbrains.buildServer.messages.serviceMessages.TestStarted
import jetbrains.buildServer.messages.serviceMessages.TestStdErr
import jetbrains.buildServer.messages.serviceMessages.TestStdOut
import jetbrains.buildServer.messages.serviceMessages.TestSuiteFinished
import jetbrains.buildServer.messages.serviceMessages.TestSuiteStarted
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.teamcity.events.TeamCityMessageProcessor
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestLocationHint
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.amper.testevents.TestFinished as AmperTestFinished
import org.jetbrains.amper.testevents.TestStarted as AmperTestStarted
import org.jetbrains.amper.testevents.TestSuiteFinished as AmperTestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteStarted as AmperTestSuiteStarted

class StructuredNativeTestProcessOutputListenerTest {
    private val renderer = RecordingRenderer()
    private val failures = mutableListOf<Pair<TestId, String>>()
    val processor = TeamCityMessageProcessor(
        eventSink = renderer,
        onTestFailed = { id, message -> failures += id to message.failureMessage },
    )
    private val listener = StructuredNativeTestProcessOutputListener(processor)
    private val runId get() = renderer.events.firstNotNullOf { it.testId }.runId

    @Test
    fun `translates TeamCity test messages into Amper test events`() {
        [
            TestSuiteStarted("SampleTest"),
            TestStarted("works", false, null),
            TestStdOut("works", "stdout"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }
        listener.onStderrLine(TestStdErr("works", "stderr").asString(), pid = 1)
        [
            TestFailed("works", "failure"),
            TestFinished("works", 42),
            TestSuiteFinished("SampleTest"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        val suiteId = TestId(runId, "SampleTest")
        val testId = TestId(runId, "SampleTest.works")
        assertEquals(
            [
                AmperTestSuiteStarted(
                    TestDescriptor(
                        suiteId,
                        null,
                        "SampleTest",
                        TestLocationHint.Class("SampleTest"),
                    )
                ),
                AmperTestStarted(
                    TestDescriptor(
                        testId,
                        suiteId,
                        "works",
                        TestLocationHint.Method("SampleTest", "works"),
                    )
                ),
                TestStdoutEvent(testId, "stdout"),
                TestStderrEvent(testId, "stderr"),
                AmperTestFinished.Failed(testId, 42.milliseconds, "failure"),
                AmperTestSuiteFinished(suiteId),
            ],
            renderer.events,
        )
    }

    @Test
    fun `uses flow parent instead of active suite for concurrent test flows`() {
        [
            flowStarted("root"),
            flowStarted("suite-a", parent = "root"),
            TestSuiteStarted("Suite A").withFlowId("suite-a"),
            flowStarted("suite-b", parent = "root"),
            TestSuiteStarted("Suite B").withFlowId("suite-b"),
            flowStarted("test-a", parent = "suite-a"),
            TestStarted("test A", false, null).withFlowId("test-a"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            AmperTestStarted(TestDescriptor(TestId(runId, "test-a"), TestId(runId, "suite-a"), "test A")),
            renderer.events.last(),
        )
    }

    @Test
    fun `uses the suite stack to disambiguate test names without flows`() {
        [
            TestSuiteStarted("First"),
            TestStarted("same", false, null),
            TestFinished("same", 0),
            TestSuiteFinished("First"),
            TestSuiteStarted("Second"),
            TestStarted("same", false, null),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            [
                AmperTestSuiteStarted(
                    TestDescriptor(
                        TestId(runId, "First"),
                        null,
                        "First",
                        TestLocationHint.Class("First"),
                    )
                ),
                AmperTestStarted(
                    TestDescriptor(
                        TestId(runId, "First.same"),
                        TestId(runId, "First"),
                        "same",
                        TestLocationHint.Method("First", "same"),
                    )
                ),
                AmperTestFinished.Succeeded(TestId(runId, "First.same"), duration = 0.milliseconds),
                AmperTestSuiteFinished(TestId(runId, "First")),
                AmperTestSuiteStarted(
                    TestDescriptor(
                        TestId(runId, "Second"),
                        null,
                        "Second",
                        TestLocationHint.Class("Second"),
                    )
                ),
                AmperTestStarted(
                    TestDescriptor(
                        TestId(runId, "Second.same"),
                        TestId(runId, "Second"),
                        "same",
                        TestLocationHint.Method("Second", "same"),
                    )
                ),
            ],
            renderer.events,
        )
    }

    @Test
    fun `maps Kotlin Native location hints`() {
        [
            TestSuiteStarted("CommonKotlinTest").withLocationHint("ktest:suite://CommonKotlinTest"),
            TestStarted("first", false, "ktest:test://CommonKotlinTest.first"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            [
                AmperTestSuiteStarted(
                    TestDescriptor(
                        TestId(runId, "CommonKotlinTest"),
                        null,
                        "CommonKotlinTest",
                        TestLocationHint.Class("CommonKotlinTest")
                    ),
                ),
                AmperTestStarted(
                    TestDescriptor(
                        TestId(runId, "CommonKotlinTest.first"),
                        TestId(runId, "CommonKotlinTest"),
                        "first",
                        TestLocationHint.Method("CommonKotlinTest", "first")
                    ),
                ),
            ],
            renderer.events,
        )
    }

    @Test
    fun `forgets a flow scope after flow finished`() {
        [
            flowStarted("suite"),
            TestSuiteStarted("Finished suite").withFlowId("suite"),
            TestSuiteFinished("Finished suite").withFlowId("suite"),
            flowFinished("suite"),
            flowStarted("later-test", parent = "suite"),
            TestStarted("later test", false, null).withFlowId("later-test"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            AmperTestStarted(TestDescriptor(TestId(runId, "later-test"), null, "later test")),
            renderer.events.last(),
        )
    }

    @Test
    fun `forwards non service message output`() {
        listener.onStdoutLine("regular stdout", pid = 1)
        listener.onStderrLine("regular stderr", pid = 1)

        assertEquals(
            [
                TestStdoutEvent(null, "regular stdout${System.lineSeparator()}"),
                TestStderrEvent(null, "regular stderr${System.lineSeparator()}"),
            ],
            renderer.events,
        )
    }

    @Test
    fun `derives location hints from the suite and test names`() {
        // kotlin-test for wasm js doesn't report any location hint, and nests the test class suite into a package suite
        [
            TestSuiteStarted("org.example"),
            TestSuiteStarted("MyTest"),
            TestStarted("myTest", false, null),
        ].forEach { processor.parseStdOut(it.asString()) }

        val packageSuiteId = TestId(runId, "org.example")
        val classSuiteId = TestId(runId, "org.example.MyTest")
        val testId = TestId(runId, "org.example.MyTest.myTest")
        assertEquals(
            [
                AmperTestSuiteStarted(
                    TestDescriptor(
                        packageSuiteId,
                        null,
                        "org.example",
                        TestLocationHint.Class("org.example"),
                    ),
                ),
                AmperTestSuiteStarted(
                    TestDescriptor(
                        classSuiteId,
                        packageSuiteId,
                        "MyTest",
                        TestLocationHint.Class("org.example.MyTest"),
                    ),
                ),
                AmperTestStarted(
                    TestDescriptor(
                        testId,
                        classSuiteId,
                        "myTest",
                        TestLocationHint.Method("org.example.MyTest", "myTest"),
                    ),
                ),
            ],
            renderer.events,
        )
    }

    @Test
    fun `doesn't derive location hints from the synthetic name of the unnamed root suite`() {
        // The package suite reported by kotlin-test for wasm js has no name at all for the root package
        [
            TestSuiteStarted(""),
            TestSuiteStarted("MyTest"),
            TestStarted("testHelloWorld", false, null),
        ].forEach { processor.parseStdOut(it.asString()) }

        val rootSuiteId = TestId(runId, "<root>")
        val classSuiteId = TestId(runId,"<root>.MyTest")
        val testId = TestId(runId,"<root>.MyTest.testHelloWorld")
        assertEquals(
            [
                AmperTestSuiteStarted(TestDescriptor(rootSuiteId, null, "<root>")),
                AmperTestSuiteStarted(
                    TestDescriptor(classSuiteId, rootSuiteId, "MyTest", TestLocationHint.Class("MyTest")),
                ),
                AmperTestStarted(
                    TestDescriptor(
                        testId,
                        classSuiteId,
                        "testHelloWorld",
                        TestLocationHint.Method("MyTest", "testHelloWorld"),
                    ),
                ),
            ],
            renderer.events,
        )
    }

    @Test
    fun `omits suites with no tests after filtering including empty ancestors`() {
        [
            TestSuiteStarted("probe"),
            TestSuiteStarted("AsyncFailureTest"),
            TestSuiteFinished("AsyncFailureTest"),
            TestSuiteFinished("probe"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals([], renderer.events)
    }

    @Test
    fun `reports selected tests immediately with their ancestors but without empty siblings`() {
        [
            TestSuiteStarted("probe"),
            TestSuiteStarted("AsyncFailureTest"),
            TestSuiteFinished("AsyncFailureTest"),
            TestSuiteStarted("ProbeTest"),
            TestStarted("synchronous", false, null),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        val startedEvents: List<TestEvent> = [
            suiteStarted("probe", null, "probe"),
            suiteStarted("probe.ProbeTest", "probe", "ProbeTest"),
            AmperTestStarted(
                TestDescriptor(
                    id("probe.ProbeTest.synchronous"), id("probe.ProbeTest"), "synchronous",
                    TestLocationHint.Method("probe.ProbeTest", "synchronous"),
                )
            ),
        ]
        assertEquals(startedEvents, renderer.events)

        [
            TestFinished("synchronous", 42),
            TestSuiteStarted("EmptyNestedSuite"),
            TestSuiteFinished("EmptyNestedSuite"),
            TestSuiteFinished("ProbeTest"),
            TestSuiteFinished("probe"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        val finishedEvents: List<TestEvent> = [
            AmperTestFinished.Succeeded(id("probe.ProbeTest.synchronous"), 42.milliseconds),
            AmperTestSuiteFinished(id("probe.ProbeTest")),
            AmperTestSuiteFinished(id("probe")),
        ]
        assertEquals(startedEvents + finishedEvents, renderer.events)
    }

    @Test
    fun `keeps suites containing only skipped tests`() {
        [
            TestSuiteStarted("probe"),
            TestSuiteStarted("IgnoredTest"),
            TestIgnored("ignored", "disabled"),
            TestSuiteFinished("IgnoredTest"),
            TestSuiteFinished("probe"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            [
                suiteStarted("probe", null, "probe"),
                suiteStarted("probe.IgnoredTest", "probe", "IgnoredTest"),
                TestSkipped(
                    TestDescriptor(id("probe.IgnoredTest.ignored"), id("probe.IgnoredTest"), "ignored"),
                    "disabled",
                ),
                AmperTestSuiteFinished(id("probe.IgnoredTest")),
                AmperTestSuiteFinished(id("probe")),
            ],
            renderer.events,
        )
    }

    @Test
    fun `preserves suite stdout and stderr even without tests`() {
        [
            TestSuiteStarted("probe"),
            TestSuiteStarted("Setup"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }
        listener.onStdoutLine("setup stdout", pid = 1)
        listener.onStderrLine("setup stderr", pid = 1)
        [
            TestSuiteFinished("Setup"),
            TestSuiteFinished("probe"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            [
                suiteStarted("probe", null, "probe"),
                suiteStarted("probe.Setup", "probe", "Setup"),
                TestStdoutEvent(id("probe.Setup"), "setup stdout${System.lineSeparator()}"),
                TestStderrEvent(id("probe.Setup"), "setup stderr${System.lineSeparator()}"),
                AmperTestSuiteFinished(id("probe.Setup")),
                AmperTestSuiteFinished(id("probe")),
            ],
            renderer.events,
        )
    }

    @Test
    fun `preserves suite service message output without tests in a flow`() {
        [
            flowStarted("suite"),
            TestSuiteStarted("Setup").withFlowId("suite"),
            TestStdErr("Setup", "setup failed").withFlowId("suite"),
            TestSuiteFinished("Setup").withFlowId("suite"),
            flowFinished("suite"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            [
                suiteStarted("suite", null, "Setup", location = null),
                TestStderrEvent(id("suite"), "setup failed"),
                AmperTestSuiteFinished(id("suite")),
            ],
            renderer.events,
        )
    }

    @Test
    fun `does not attach unscoped output to a pending flow suite`() {
        [
            flowStarted("suite"),
            TestSuiteStarted("Filtered").withFlowId("suite"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }
        listener.onStdoutLine("process stdout", pid = 1)
        listener.onStderrLine("process stderr", pid = 1)
        [
            TestSuiteFinished("Filtered").withFlowId("suite"),
            flowFinished("suite"),
        ].forEach { listener.onStdoutLine(it.asString(), pid = 1) }

        assertEquals(
            [
                TestStdoutEvent(null, "process stdout${System.lineSeparator()}"),
                TestStderrEvent(null, "process stderr${System.lineSeparator()}"),
            ],
            renderer.events,
        )
    }

    private class RecordingRenderer : EventSink<TestEvent> {
        val events: List<TestEvent>
            field = mutableListOf()

        override fun emit(event: TestEvent) {
            events += event
        }
    }

    private fun flowStarted(flowId: String, parent: String? = null): MessageWithAttributes =
        object : MessageWithAttributes(
            ServiceMessageTypes.FLOW_STARTED,
            buildMap {
                put("flowId", flowId)
                parent?.let { put("parent", it) }
            },
        ) {}

    private fun flowFinished(flowId: String): MessageWithAttributes = object : MessageWithAttributes(
        ServiceMessageTypes.FLOW_FINSIHED,
        mapOf("flowId" to flowId),
    ) {}

    private fun <T : MessageWithAttributes> T.withFlowId(flowId: String): T = apply { setFlowId(flowId) }

    private fun <T : MessageWithAttributes> T.withLocationHint(locationHint: String): ServiceMessage =
        object : ServiceMessage(
            messageName,
            buildMap {
                putAll(attributes)
                put("locationHint", locationHint)
            }
        ) {}

    private fun id(value: String): TestId = TestId(runId, value)

    private fun suiteStarted(
        value: String,
        parent: String?,
        name: String,
        location: TestLocationHint? = TestLocationHint.Class(value),
    ): AmperTestSuiteStarted = AmperTestSuiteStarted(TestDescriptor(id(value), parent?.let(::id), name, location))

    private fun TeamCityMessageProcessor.parseStdOut(line: String) = parse(line, stderr = false)
}

