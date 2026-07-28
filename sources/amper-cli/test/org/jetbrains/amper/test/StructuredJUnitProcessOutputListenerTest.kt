/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test

import org.jetbrains.amper.junit.event.JUnitEventProtocol
import org.jetbrains.amper.tasks.jvm.StructuredJUnitProcessOutputListener
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private class RecordingRenderer : TestEventRenderer {
        val events: List<TestEvent>
         field = mutableListOf()

        override fun render(event: TestEvent) {
            events += event
        }
    }
}
