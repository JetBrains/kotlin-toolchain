/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.junit.xml

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class JUnitXmlReportTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `report records test identity counts and duration`() {
        val file = directory / "reports" / "TEST-example.xml"
        JUnitXmlReport("example", [JUnitXmlTestCase("succeeds", "example.Test", 125.milliseconds)]).writeTo(file)

        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        val suite = xml.documentElement
        assertEquals("testsuite", suite.tagName)
        assertEquals("example", suite.getAttribute("name"))
        assertEquals("1", suite.getAttribute("tests"))
        assertEquals("0", suite.getAttribute("failures"))
        assertEquals("0", suite.getAttribute("errors"))
        assertEquals("0", suite.getAttribute("skipped"))
        assertEquals("0.125", suite.getAttribute("time"))
        val case = xml.getElementsByTagName("testcase").item(0).attributes
        assertEquals("succeeds", case.getNamedItem("name").nodeValue)
        assertEquals("example.Test", case.getNamedItem("classname").nodeValue)
        assertEquals("0.125", case.getNamedItem("time").nodeValue)
    }

    @Test
    fun `report distinguishes failures errors and skipped tests and keeps output`() {
        val file = directory / "TEST-results.xml"
        JUnitXmlReport("results", [
            JUnitXmlTestCase("passes", "Example", 1.seconds),
            JUnitXmlTestCase("fails", "Example", 2.seconds, JUnitXmlResult.Failure("wrong", "trace"), "out", "err"),
            JUnitXmlTestCase("interrupted", "Example", 3.seconds, JUnitXmlResult.Error("timeout")),
            JUnitXmlTestCase("ignored", "Example", 0.seconds, JUnitXmlResult.Skipped("disabled")),
        ]).writeTo(file)

        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        assertEquals("4", xml.documentElement.getAttribute("tests"))
        assertEquals("1", xml.documentElement.getAttribute("failures"))
        assertEquals("1", xml.documentElement.getAttribute("errors"))
        assertEquals("1", xml.documentElement.getAttribute("skipped"))
        assertEquals(6.0, xml.documentElement.getAttribute("time").toDouble())
        assertEquals("wrong", xml.getElementsByTagName("failure").item(0).attributes.getNamedItem("message").nodeValue)
        assertEquals("trace", xml.getElementsByTagName("failure").item(0).textContent)
        assertEquals("timeout", xml.getElementsByTagName("error").item(0).textContent)
        assertEquals("disabled", xml.getElementsByTagName("skipped").item(0).attributes.getNamedItem("message").nodeValue)
        assertEquals("out", xml.getElementsByTagName("system-out").item(0).textContent)
        assertEquals("err", xml.getElementsByTagName("system-err").item(0).textContent)
    }

    @Test
    fun `XML preserves special characters and replaces characters forbidden by XML 1_0`() {
        val file = directory / "TEST-escaping.xml"
        val text = "<&>\"'|[]\n\r\t中文😀"
        JUnitXmlReport(text, [
            JUnitXmlTestCase(text, text, 0.seconds, JUnitXmlResult.Failure(text, "$text\u0000\u000B\uD800")),
        ]).writeTo(file)

        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        assertEquals(text, xml.documentElement.getAttribute("name"))
        assertEquals(text, xml.getElementsByTagName("testcase").item(0).attributes.getNamedItem("name").nodeValue)
        assertEquals(text, xml.getElementsByTagName("failure").item(0).attributes.getNamedItem("message").nodeValue)
        assertEquals("$text\uFFFD\uFFFD\uFFFD", xml.getElementsByTagName("failure").item(0).textContent)
    }

    @Test
    fun `empty run replaces an earlier report and preserves unattributed output`() {
        val file = directory / "TEST-repeat.xml"
        JUnitXmlReport("old", [JUnitXmlTestCase("old test", "Example", 1.seconds)]).writeTo(file)
        JUnitXmlReport("empty", [], stdout = "no matching tests", stderr = "a warning").writeTo(file)

        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        assertEquals("0", xml.documentElement.getAttribute("tests"))
        assertEquals(0, xml.getElementsByTagName("testcase").length)
        assertEquals("no matching tests", xml.getElementsByTagName("system-out").item(0).textContent)
        assertEquals("a warning", xml.getElementsByTagName("system-err").item(0).textContent)
        assertEquals([file], directory.listDirectoryEntries())
    }

    @Test
    fun `short durations use decimal seconds instead of scientific notation`() {
        val file = directory / "TEST-duration.xml"
        JUnitXmlReport("short", [JUnitXmlTestCase("quick", "Example", 1.nanoseconds)]).writeTo(file)

        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        assertEquals("0.000000001", xml.documentElement.getAttribute("time"))
        assertEquals("0.000000001", xml.getElementsByTagName("testcase").item(0).attributes.getNamedItem("time").nodeValue)
    }
}
