/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.junit.event.JUnitEventProtocol
import org.jetbrains.amper.processes.output.ProcessOutputListener
import org.jetbrains.amper.testevents.TestDescriptor
import org.jetbrains.amper.testevents.TestEvent
import org.jetbrains.amper.testevents.TestFinished
import org.jetbrains.amper.testevents.TestId
import org.jetbrains.amper.testevents.TestLocationHint
import org.jetbrains.amper.testevents.TestReportEvent
import org.jetbrains.amper.testevents.TestSkipped
import org.jetbrains.amper.testevents.TestStarted
import org.jetbrains.amper.testevents.TestStderrEvent
import org.jetbrains.amper.testevents.TestStdoutEvent
import org.jetbrains.amper.testevents.TestSuiteAborted
import org.jetbrains.amper.testevents.TestSuiteFailed
import org.jetbrains.amper.testevents.TestSuiteFinished
import org.jetbrains.amper.testevents.TestSuiteSkipped
import org.jetbrains.amper.testevents.TestSuiteStarted
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Translates private JUnit records to Kotlin Toolchain events.
 */
internal class StructuredJUnitProcessOutputListener(
    private val eventSink: EventSink<TestEvent>,
) : ProcessOutputListener {
    override fun onStdoutLine(line: String, pid: Long) {
        JUnitEventProtocol.decode(line)?.let(::emit) ?: emit(TestStdoutEvent(null, "$line${System.lineSeparator()}"))
    }

    override fun onStderrLine(line: String, pid: Long) {
        JUnitEventProtocol.decode(line)?.let(::emit) ?: emit(TestStderrEvent(null, "$line${System.lineSeparator()}"))
    }

    private fun JUnitEventProtocol.Event.toTestEvent(): TestEvent = when (this) {
        is JUnitEventProtocol.Event.TestStdout -> TestStdoutEvent(id?.let(::TestId), text)
        is JUnitEventProtocol.Event.TestStderr -> TestStderrEvent(id?.let(::TestId), text)
        is JUnitEventProtocol.Event.SuiteStarted -> TestSuiteStarted(
            TestDescriptor(
                TestId(id),
                parentId?.let(::TestId),
                displayName,
                location?.toTestLocationHint(),
                teamCityName
            ),
        )
        is JUnitEventProtocol.Event.TestStarted -> TestStarted(
            TestDescriptor(
                TestId(id),
                parentId?.let(::TestId),
                displayName,
                location?.toTestLocationHint(),
                teamCityName
            ),
        )
        is JUnitEventProtocol.Event.SuiteFinished -> TestSuiteFinished(TestId(id), durationMillis?.milliseconds)
        is JUnitEventProtocol.Event.SuiteAborted -> TestSuiteAborted(TestId(id), durationMillis?.milliseconds, abortMessage)
        is JUnitEventProtocol.Event.SuiteFailed -> TestSuiteFailed(
            testId = TestId(id),
            duration = durationMillis?.milliseconds,
            failureMessage = failureMessage,
            stackTrace = stackTrace,
            expected = expected,
            actual = actual,
            expectedFilePath = expectedFilePath,
            actualFilePath = actualFilePath,
        )
        is JUnitEventProtocol.Event.SuiteSkipped -> TestSuiteSkipped(
            TestDescriptor(
                TestId(id),
                parentId?.let(::TestId),
                displayName,
                location?.toTestLocationHint(),
                teamCityName,
            ),
            reason,
        )
        is JUnitEventProtocol.Event.Succeeded -> TestFinished.Succeeded(TestId(id), durationMillis?.milliseconds)
        is JUnitEventProtocol.Event.TestAborted -> TestFinished.Aborted(
            TestId(id), durationMillis?.milliseconds, abortMessage,
        )
        is JUnitEventProtocol.Event.TestSkipped -> TestSkipped(
            TestDescriptor(
                TestId(id),
                parentId?.let(::TestId),
                displayName,
                location?.toTestLocationHint(),
                teamCityName,
            ),
            reason,
        )
        is JUnitEventProtocol.Event.Failed -> TestFinished.Failed(
            testId = TestId(id), duration = durationMillis?.milliseconds, failureMessage = failureMessage,
            stackTrace = stackTrace, expected = expected, actual = actual,
            expectedFilePath = expectedFilePath, actualFilePath = actualFilePath,
        )
        is JUnitEventProtocol.Event.Report -> TestReportEvent(
            testId = TestId(id),
            key = key,
            value = value,
            mediaType = mediaType,
            timestamp = timestampMillis?.let(Instant::fromEpochMilliseconds),
        )
    }

    private fun emit(event: JUnitEventProtocol.Event) = eventSink.emit(event.toTestEvent())

    private fun emit(event: TestEvent) = eventSink.emit(event)

    private fun JUnitEventProtocol.Location.toTestLocationHint(): TestLocationHint = when (this) {
        is JUnitEventProtocol.Location.Class -> TestLocationHint.Class(className)
        is JUnitEventProtocol.Location.Method -> TestLocationHint.Method(className, methodName, methodParameterTypes)
        is JUnitEventProtocol.Location.Uri -> TestLocationHint.Uri(uri)
    }
}
