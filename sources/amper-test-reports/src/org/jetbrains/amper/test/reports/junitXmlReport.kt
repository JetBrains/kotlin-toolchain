/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.reports

import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.junit.xml.JUnitXmlReport
import org.jetbrains.amper.junit.xml.JUnitXmlResult
import org.jetbrains.amper.junit.xml.JUnitXmlTestCase
import org.jetbrains.amper.testevents.*
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Collects a single task's test events without changing the events delivered to other consumers. */
suspend fun <T> withJUnitXmlReport(path: Path, name: String, action: suspend (EventSink<TestEvent>) -> T): T {
    val report = TestReport(name)
    var failure: Throwable? = null
    try {
        return action(report)
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        try {
            report.snapshot(failure).writeTo(path)
        } catch (reportError: Throwable) {
            if (failure == null) throw reportError
            failure.addSuppressed(reportError)
        }
    }
}

private class TestReport(private val name: String) : EventSink<TestEvent> {
    private val suites = mutableMapOf<TestId, TestDescriptor>()
    private val tests = mutableMapOf<TestId, TestState>()
    private val stdout = StringBuilder()
    private val stderr = StringBuilder()

    @Synchronized
    override fun emit(event: TestEvent) {
        when (event) {
            is TestSuiteStarted -> suites[event.testId] = event.descriptor
            is TestStarted -> tests[event.testId] = TestState(event.descriptor)
            is TestSkipped -> tests[event.testId] = TestState(event.descriptor).apply {
                duration = Duration.ZERO
                result = JUnitXmlResult.Skipped(event.reason)
            }
            is TestFinished -> finish(event)
            is TestStdoutEvent -> (tests[event.testId]?.stdout ?: stdout).append(event.text)
            is TestStderrEvent -> (tests[event.testId]?.stderr ?: stderr).append(event.text)
            else -> Unit
        }
    }

    private fun finish(event: TestFinished) {
        val test = tests[event.testId] ?: return
        test.duration = event.duration ?: test.startedAt.elapsedNow()
        test.result = when (event) {
            is TestFinished.Succeeded -> JUnitXmlResult.Success
            is TestFinished.Aborted -> JUnitXmlResult.Error(event.abortMessage)
            is TestFinished.Failed -> JUnitXmlResult.Failure(event.failureMessage, buildString {
                appendLine(event.failureMessage)
                if (event.expected != null) appendLine("Expected: ${event.expected}")
                if (event.actual != null) appendLine("Actual: ${event.actual}")
                if (event.stackTrace != null) append(event.stackTrace)
            })
        }
    }

    @Synchronized
    fun snapshot(failure: Throwable?): JUnitXmlReport = JUnitXmlReport(
        name = name,
        tests = tests.values.map { test ->
            JUnitXmlTestCase(
                name = test.descriptor.displayName,
                className = className(test.descriptor),
                duration = test.duration ?: test.startedAt.elapsedNow(),
                result = test.result ?: JUnitXmlResult.Error(
                    "Test run did not complete",
                    "Test run did not complete\n${failure?.stackTraceToString().orEmpty()}",
                ),
                stdout = test.stdout.toString(),
                stderr = test.stderr.toString(),
            )
        } + runnerErrors(failure),
        stdout = stdout.toString(),
        stderr = stderr.toString(),
    )

    private fun runnerErrors(failure: Throwable?): List<JUnitXmlTestCase> {
        if (failure == null || tests.values.any { it.result == null }) return []
        return [JUnitXmlTestCase(
            name = "Test runner",
            className = name,
            duration = Duration.ZERO,
            result = JUnitXmlResult.Error(
                failure.message ?: "Test run did not complete",
                failure.stackTraceToString(),
            ),
        )]
    }

    private fun className(descriptor: TestDescriptor): String {
        val location = descriptor.location
        if (location is TestLocationHint.Method) return location.className
        val suite = suites[descriptor.parentId] ?: return name
        return (suite.location as? TestLocationHint.Class)?.className ?: suite.displayName
    }
}

private class TestState(val descriptor: TestDescriptor) {
    val startedAt = TimeSource.Monotonic.markNow()
    var duration: Duration? = null
    var result: JUnitXmlResult? = null
    val stdout = StringBuilder()
    val stderr = StringBuilder()
}
