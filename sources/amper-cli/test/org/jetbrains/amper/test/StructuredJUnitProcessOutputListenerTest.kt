/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import org.jetbrains.amper.junit.event.JUnitEventProtocol
import org.jetbrains.amper.tasks.jvm.StructuredJUnitProcessOutputListener
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.jetbrains.amper.testevents.TestSuiteAborted
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class StructuredJUnitProcessOutputListenerTest {
    @Test
    fun `renders protocol output as test output events`() {
        val renderer = RecordingRenderer()
        val listener = StructuredJUnitProcessOutputListener(renderer = renderer)

        listener.onStdoutLine(JUnitEventProtocol.encode(JUnitEventProtocol.Event.TestStdout("test", "protocol stdout")), pid = 1)
        listener.onStderrLine(JUnitEventProtocol.encode(JUnitEventProtocol.Event.TestStderr("test", "protocol stderr")), pid = 1)
        listener.onStdoutLine(JUnitEventProtocol.encode(JUnitEventProtocol.Event.TestStdout(null, "unattributed stdout")), pid = 1)
        listener.onStdoutLine("regular stdout", pid = 1)
        listener.onStderrLine("regular stderr", pid = 1)

        assertEquals(
            [
                TestStdoutEvent(TestId("test"), "protocol stdout"),
                TestStderrEvent(TestId("test"), "protocol stderr"),
                TestStdoutEvent(null, "unattributed stdout"),
                TestStdoutEvent(null, "regular stdout${System.lineSeparator()}"),
                TestStderrEvent(null, "regular stderr${System.lineSeparator()}"),
            ],
            renderer.events,
        )
    }

    @Test
    fun `preserves a report media type`() {
        val renderer = RecordingRenderer()
        val listener = StructuredJUnitProcessOutputListener(renderer = renderer)

        listener.onStdoutLine(
            JUnitEventProtocol.encode(
                JUnitEventProtocol.Event.Report(
                    id = "test",
                    key = "attachment",
                    value = "/tmp/result.png",
                    mediaType = "image/png",
                    timestampMillis = 1_234L,
                )
            ),
            pid = 1,
        )

        val expected = [
            TestReportEvent(
                testId = TestId("test"),
                key = "attachment",
                value = "/tmp/result.png",
                mediaType = "image/png",
                timestamp = Instant.fromEpochMilliseconds(1_234L),
            )
        ]

        assertEquals(expected, renderer.events)
    }

    @Test
    @Disabled("Kotlin CLI needs to bootstrap to launch this test correctly. Otherwise, the old version of amper-junit-event-protocol classes added on the runtime take precedence.")
    fun `converts aborted and skipped protocol events`() {
        val renderer = RecordingRenderer()
        val listener = StructuredJUnitProcessOutputListener(renderer = renderer)
        val descriptor = TestDescriptor(TestId("test"), TestId("suite"), "Skipped test")
        val suiteDescriptor = TestDescriptor(TestId("suite"), null, "Skipped suite")

        [
            JUnitEventProtocol.Event.SuiteAborted("suite", 10, "suite aborted"),
            JUnitEventProtocol.Event.SuiteSkipped(
                "skippedSuite",
                null,
                "Skipped suite",
                null,
                reason = "suite skipped"
            ),
            JUnitEventProtocol.Event.TestAborted("test", 20, "test aborted"),
            JUnitEventProtocol.Event.TestSkipped("test", "suite", "Skipped test", null, reason = "test skipped"),
        ].forEach { listener.onStdoutLine(JUnitEventProtocol.encode(it), pid = 1) }

        assertEquals(
            [
                TestSuiteAborted(TestId("suite"), 10.milliseconds, "suite aborted"),
                TestSuiteSkipped(suiteDescriptor.copy(id = TestId("skippedSuite")), "suite skipped"),
                TestFinished.Aborted(TestId("test"), 20.milliseconds, "test aborted"),
                TestSkipped(descriptor, "test skipped"),
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
}
