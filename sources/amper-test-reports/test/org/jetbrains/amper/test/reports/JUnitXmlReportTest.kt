/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.test.reports

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.jetbrains.amper.events.sink.EventSink
import org.jetbrains.amper.teamcity.events.TeamCityMessageProcessor
import org.jetbrains.amper.testevents.TestEvent
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class JUnitXmlReportTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `TeamCity test results are written without counting their suites as tests`() = runTest {
        val file = directory / "TEST-browser.xml"
        val result = withJUnitXmlReport(file, "browser") { sink ->
            val processor = TeamCityMessageProcessor(sink)
            val messages: List<String> = [
                "testSuiteStarted name='example'",
                "testSuiteStarted name='ExampleTest'",
                "testStarted name='passes'",
                "testFinished name='passes' duration='125'",
                "testStarted name='fails'",
                "testFailed name='fails' message='expected |'<&>|'' details='line 1|nline 2' expected='1' actual='2'",
                "testFinished name='fails' duration='75'",
                "testIgnored name='ignored' message='disabled'",
                "testSuiteFinished name='ExampleTest'",
                "testSuiteStarted name='EmptyTest'",
                "testSuiteFinished name='EmptyTest'",
                "testSuiteFinished name='example'",
            ]
            messages.forEach { processor.parse("##teamcity[$it]", stderr = false) }
            "returned value"
        }

        assertEquals("returned value", result)
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        assertEquals("3", xml.documentElement.getAttribute("tests"))
        assertEquals("1", xml.documentElement.getAttribute("failures"))
        assertEquals("1", xml.documentElement.getAttribute("skipped"))
        assertEquals("0", xml.documentElement.getAttribute("errors"))
        assertEquals("example.ExampleTest", xml.getElementsByTagName("testcase").item(0).attributes.getNamedItem("classname").nodeValue)
        assertEquals("expected '<&>'", xml.getElementsByTagName("failure").item(0).attributes.getNamedItem("message").nodeValue)
    }

    @Test
    fun `cancellation saves completed tests and marks the running test as an error`() = runTest {
        val file = directory / "TEST-cancelled.xml"
        val started = CompletableDeferred<Unit>()
        val run = launch {
            withJUnitXmlReport(file, "cancelled") { sink ->
                startIncompleteRun(sink)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        run.cancelAndJoin()

        assertInterruptedReport(file)
    }

    @Test
    fun `timeout is rethrown after writing the partial report`() = runTest {
        val file = directory / "TEST-timeout.xml"
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) {
                withJUnitXmlReport(file, "timeout") { sink ->
                    startIncompleteRun(sink)
                    awaitCancellation()
                }
            }
        }
        assertInterruptedReport(file)
    }

    @Test
    fun `a runner failure without a running test is visible in the report`() = runTest {
        val file = directory / "TEST-page-error.xml"
        val failure = IllegalStateException("page crashed <before tests>")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            withJUnitXmlReport(file, "browser") { throw failure }
        })

        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        assertEquals("1", xml.documentElement.getAttribute("tests"))
        assertEquals("1", xml.documentElement.getAttribute("errors"))
        assertContains(xml.getElementsByTagName("error").item(0).textContent, "page crashed <before tests>")
    }

    @Test
    fun `report writing errors do not hide the original runner failure`() = runTest {
        val parent = directory / "a-file"
        parent.writeText("not a directory")
        val failure = IllegalStateException("original failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            withJUnitXmlReport(parent / "report.xml", "browser") { throw failure }
        })
        assertEquals(1, failure.suppressed.size)
    }

    @Test
    fun `a successful run cannot silently lose its report`() = runTest {
        val parent = directory / "a-file"
        parent.writeText("not a directory")
        assertFailsWith<IOException> {
            withJUnitXmlReport(parent / "report.xml", "browser") { "success" }
        }
    }

    private fun startIncompleteRun(sink: EventSink<TestEvent>) {
        val processor = TeamCityMessageProcessor(sink)
        processor.parse("##teamcity[testStarted name='completed']", false)
        processor.parse("##teamcity[testFinished name='completed']", false)
        processor.parse("##teamcity[testStarted name='stillRunning']", false)
    }

    private fun assertInterruptedReport(file: Path) {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        assertEquals("2", xml.documentElement.getAttribute("tests"))
        assertEquals("0", xml.documentElement.getAttribute("failures"))
        assertEquals("1", xml.documentElement.getAttribute("errors"))
        assertEquals("stillRunning", xml.getElementsByTagName("error").item(0).parentNode.attributes.getNamedItem("name").nodeValue)
        assertContains(xml.getElementsByTagName("error").item(0).textContent, "Test run did not complete")
    }
}
