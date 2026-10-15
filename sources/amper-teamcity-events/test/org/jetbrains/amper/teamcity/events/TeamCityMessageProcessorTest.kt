/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.teamcity.events

import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestLocationHint
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.jetbrains.amper.testevents.TestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteStarted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class TeamCityMessageProcessorTest {
    private val events = mutableListOf<TestEvent>()
    private val failures = mutableListOf<Pair<TestId, String>>()
    private val processor = TeamCityMessageProcessor(
        eventSink = object : EventSink<TestEvent> {
            override fun emit(event: TestEvent) {
                events += event
            }
        },
        onTestFailed = { id, message -> failures += id to message.failureMessage },
    )

    @Test
    fun `omits suites with no tests after filtering including empty ancestors`() {
        parse(
            """
            ##teamcity[testSuiteStarted name='probe']
            ##teamcity[testSuiteStarted name='AsyncFailureTest']
            ##teamcity[testSuiteFinished name='AsyncFailureTest']
            ##teamcity[testSuiteFinished name='probe']
            """
        )

        assertEvents([])
    }

    @Test
    fun `reports selected tests immediately with their ancestors but without empty siblings`() {
        parse(
            """
            ##teamcity[testSuiteStarted name='probe']
            ##teamcity[testSuiteStarted name='AsyncFailureTest']
            ##teamcity[testSuiteFinished name='AsyncFailureTest']
            ##teamcity[testSuiteStarted name='ProbeTest']
            ##teamcity[testStarted name='synchronous']
            """
        )

        val startedEvents: List<TestEvent> = [
            suiteStarted("probe", null, "probe"),
            suiteStarted("probe.ProbeTest", "probe", "ProbeTest"),
            TestStarted(
                TestDescriptor(
                    id("probe.ProbeTest.synchronous"), id("probe.ProbeTest"), "synchronous",
                    TestLocationHint.Method("probe.ProbeTest", "synchronous"),
                )
            ),
        ]
        assertEvents(startedEvents)

        parse(
            """
            ##teamcity[testFinished name='synchronous' duration='42']
            ##teamcity[testSuiteStarted name='EmptyNestedSuite']
            ##teamcity[testSuiteFinished name='EmptyNestedSuite']
            ##teamcity[testSuiteFinished name='ProbeTest']
            ##teamcity[testSuiteStarted name='OtherFilteredTest']
            ##teamcity[testSuiteFinished name='OtherFilteredTest']
            ##teamcity[testSuiteFinished name='probe']
            """
        )

        val finishedEvents: List<TestEvent> = [
            TestFinished.Succeeded(id("probe.ProbeTest.synchronous"), 42.milliseconds),
            TestSuiteFinished(id("probe.ProbeTest")),
            TestSuiteFinished(id("probe")),
        ]
        assertEvents(startedEvents + finishedEvents)
    }

    @Test
    fun `keeps suites containing only skipped tests and clears the skipped test from the stack`() {
        parse(
            """
            ##teamcity[testSuiteStarted name='probe']
            ##teamcity[testSuiteStarted name='IgnoredTest']
            ##teamcity[testIgnored name='ignored' message='disabled']
            ##teamcity[testSuiteFinished name='IgnoredTest']
            ##teamcity[testSuiteStarted name='FilteredTest']
            ##teamcity[testSuiteFinished name='FilteredTest']
            ##teamcity[testSuiteFinished name='probe']
            """
        )

        assertEvents(
            [
                suiteStarted("probe", null, "probe"),
                suiteStarted("probe.IgnoredTest", "probe", "IgnoredTest"),
                TestSkipped(
                    TestDescriptor(id("probe.IgnoredTest.ignored"), id("probe.IgnoredTest"), "ignored"),
                    "disabled",
                ),
                TestSuiteFinished(id("probe.IgnoredTest")),
                TestSuiteFinished(id("probe")),
            ],
        )
    }

    @Test
    fun `keeps failure details and test output while omitting an empty concurrent flow`() {
        parse(
            """
            ##teamcity[flowStarted flowId='root']
            ##teamcity[testSuiteStarted name='Root' flowId='root']
            ##teamcity[flowStarted flowId='empty' parent='root']
            ##teamcity[testSuiteStarted name='Empty' flowId='empty']
            ##teamcity[flowStarted flowId='suite' parent='root']
            ##teamcity[testSuiteStarted name='Suite' flowId='suite' locationHint='ktest:suite://probe.ProbeTest']
            ##teamcity[flowStarted flowId='test' parent='suite']
            ##teamcity[testStarted name='fails' flowId='test' locationHint='ktest:test://probe.ProbeTest.fails']
            ##teamcity[testSuiteFinished name='Empty' flowId='empty']
            ##teamcity[flowFinished flowId='empty']
            ##teamcity[testStdOut name='fails' flowId='test' out='stdout']
            ##teamcity[testStdErr name='fails' flowId='test' out='stderr']
            ##teamcity[testFailed name='fails' flowId='test' message='failure' details='stack' expected='yes' actual='no']
            ##teamcity[testFinished name='fails' flowId='test' duration='12']
            ##teamcity[flowFinished flowId='test']
            ##teamcity[testSuiteFinished name='Suite' flowId='suite']
            ##teamcity[flowFinished flowId='suite']
            ##teamcity[testSuiteFinished name='Root' flowId='root']
            ##teamcity[flowFinished flowId='root']
            """
        )

        assertEvents(
            [
                suiteStarted("root", null, "Root", location = null),
                suiteStarted("suite", "root", "Suite", TestLocationHint.Class("probe.ProbeTest")),
                TestStarted(
                    TestDescriptor(
                        id("test"), id("suite"), "fails", TestLocationHint.Method("probe.ProbeTest", "fails"),
                    )
                ),
                TestStdoutEvent(id("test"), "stdout"),
                TestStderrEvent(id("test"), "stderr"),
                TestFinished.Failed(id("test"), 12.milliseconds, "failure", "stack", "yes", "no"),
                TestSuiteFinished(id("suite")),
                TestSuiteFinished(id("root")),
            ],
        )
        assertEquals([id("test") to "failure"], failures)
    }

    @Test
    fun `preserves suite stdout and stderr even without tests`() {
        parse(
            """
            ##teamcity[testSuiteStarted name='probe']
            ##teamcity[testSuiteStarted name='Setup']
            setup stdout
            """
        )
        processor.parse("setup stderr", stderr = true)
        parse(
            """
            ##teamcity[testSuiteFinished name='Setup']
            ##teamcity[testSuiteFinished name='probe']
            """
        )

        assertEvents(
            [
                suiteStarted("probe", null, "probe"),
                suiteStarted("probe.Setup", "probe", "Setup"),
                TestStdoutEvent(id("probe.Setup"), "setup stdout${System.lineSeparator()}"),
                TestStderrEvent(id("probe.Setup"), "setup stderr${System.lineSeparator()}"),
                TestSuiteFinished(id("probe.Setup")),
                TestSuiteFinished(id("probe")),
            ],
        )
    }

    @Test
    fun `preserves suite service message output without tests in a flow`() {
        parse(
            """
            ##teamcity[flowStarted flowId='suite']
            ##teamcity[testSuiteStarted name='Setup' flowId='suite']
            ##teamcity[testStdErr name='Setup' flowId='suite' out='setup failed']
            ##teamcity[testSuiteFinished name='Setup' flowId='suite']
            ##teamcity[flowFinished flowId='suite']
            """
        )

        assertEvents(
            [
                suiteStarted("suite", null, "Setup", location = null),
                TestStderrEvent(id("suite"), "setup failed"),
                TestSuiteFinished(id("suite")),
            ],
        )
    }

    @Test
    fun `does not attach unscoped output to a pending flow suite`() {
        parse(
            """
            ##teamcity[flowStarted flowId='suite']
            ##teamcity[testSuiteStarted name='Filtered' flowId='suite']
            process stdout
            """
        )
        processor.parse("process stderr", stderr = true)
        parse(
            """
            ##teamcity[testSuiteFinished name='Filtered' flowId='suite']
            ##teamcity[flowFinished flowId='suite']
            """
        )

        assertEvents(
            [
                TestStdoutEvent(null, "process stdout${System.lineSeparator()}"),
                TestStderrEvent(null, "process stderr${System.lineSeparator()}"),
            ],
        )
    }

    private fun assertEvents(expected: List<TestEvent>) {
        assertEquals(expected, events)
    }

    private fun id(value: String): TestId = TestId(events.firstNotNullOf { it.testId }.runId, value)

    private fun suiteStarted(
        value: String,
        parent: String?,
        name: String,
        location: TestLocationHint? = TestLocationHint.Class(value),
    ): TestSuiteStarted = TestSuiteStarted(TestDescriptor(id(value), parent?.let(::id), name, location))

    private fun parse(messages: String) {
        messages.trimIndent().lines().forEach { processor.parse(it, stderr = false) }
    }
}
