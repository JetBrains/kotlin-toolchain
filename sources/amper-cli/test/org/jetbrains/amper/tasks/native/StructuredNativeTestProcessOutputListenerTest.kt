/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native

import jetbrains.buildServer.messages.serviceMessages.MessageWithAttributes
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes
import jetbrains.buildServer.messages.serviceMessages.TestFailed
import jetbrains.buildServer.messages.serviceMessages.TestFinished
import jetbrains.buildServer.messages.serviceMessages.TestStarted
import jetbrains.buildServer.messages.serviceMessages.TestStdErr
import jetbrains.buildServer.messages.serviceMessages.TestStdOut
import jetbrains.buildServer.messages.serviceMessages.TestSuiteFinished
import jetbrains.buildServer.messages.serviceMessages.TestSuiteStarted
import org.jetbrains.amper.test.TestEventRenderer
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestLocationHint
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
    private val listener = StructuredNativeTestProcessOutputListener(renderer)

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

        val suiteId = TestId("SampleTest")
        val testId = TestId("SampleTest.works")
        assertEquals(
            [
                AmperTestSuiteStarted(TestDescriptor(suiteId, null, "SampleTest")),
                AmperTestStarted(TestDescriptor(testId, suiteId, "works")),
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
            AmperTestStarted(TestDescriptor(TestId("test-a"), TestId("suite-a"), "test A")),
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
                AmperTestSuiteStarted(TestDescriptor(TestId("First"), null, "First")),
                AmperTestStarted(TestDescriptor(TestId("First.same"), TestId("First"), "same")),
                AmperTestFinished.Succeeded(TestId("First.same"), duration = 0.milliseconds),
                AmperTestSuiteFinished(TestId("First")),
                AmperTestSuiteStarted(TestDescriptor(TestId("Second"), null, "Second")),
                AmperTestStarted(TestDescriptor(TestId("Second.same"), TestId("Second"), "same")),
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
                        TestId("CommonKotlinTest"),
                        null,
                        "CommonKotlinTest",
                        TestLocationHint.Class("CommonKotlinTest")
                    ),
                ),
                AmperTestStarted(
                    TestDescriptor(
                        TestId("CommonKotlinTest.first"),
                        TestId("CommonKotlinTest"),
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
            AmperTestStarted(TestDescriptor(TestId("later-test"), null, "later test")),
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

    private class RecordingRenderer : TestEventRenderer {
        val events: List<TestEvent>
            field = mutableListOf()

        override fun render(event: TestEvent) {
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
}

