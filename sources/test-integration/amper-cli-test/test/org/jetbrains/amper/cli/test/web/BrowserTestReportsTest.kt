/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.web

import org.jetbrains.amper.cli.test.CliTestBase
import org.jetbrains.amper.cli.test.utils.assertFileExists
import org.jetbrains.amper.cli.test.utils.assertStderrContains
import org.jetbrains.amper.cli.test.utils.assertStdoutContains
import org.jetbrains.amper.cli.test.utils.runSlowTest
import org.junit.jupiter.api.Tag
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@Tag("cli-test-group-web")
class BrowserTestReportsTest : CliTestBase() {
    @Test
    fun `browser tests write a JUnit XML report`() = runSlowTest {
        val projectDir = newEmptyProjectDir(setupWrappers = true)
        (projectDir / "module.yaml").writeText("product: wasm-js/app")
        (projectDir / "test" / "ExampleTest.kt").createParentDirectories().writeText(
            """
            package example
            import kotlin.test.Test
            class ExampleTest {
                @Test fun succeeds() {}
            }
            """.trimIndent()
        )

        val result = runCli(projectDir, "test")

        val report = result.buildDir / "reports" / "new" / "wasmJs" / "debug" / "TEST-browser.xml"
        assertFileExists(report)
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report.toFile())
        assertEquals("1", xml.documentElement.getAttribute("tests"))
        assertEquals("0", xml.documentElement.getAttribute("failures"))
        assertEquals(1, xml.getElementsByTagName("testcase").length)
    }

    @Test
    fun `browser report directory is configurable independently of console format`() = runSlowTest {
        val projectDir = newEmptyProjectDir(setupWrappers = true)
        (projectDir / "module.yaml").writeText("product: wasm-js/app")
        (projectDir / "test" / "ExampleTest.kt").createParentDirectories().writeText(
            "import kotlin.test.Test\nclass ExampleTest { @Test fun succeeds() {} }"
        )
        val result = runCli(projectDir, "test", "--reports-dir=ci reports", "--format=teamcity")

        assertFileExists(projectDir / "ci reports" / "new" / "wasmJs" / "debug" / "TEST-browser.xml")
        assertFalse((result.buildDir / "reports").exists())
    }

    @Test
    fun `browser reports synchronous and asynchronous outcomes without changing TeamCity results`() = runSlowTest {
        val result = runCli(
            testProject("wasm-js-test-reports"), "test", "--include-classes=example.ReportingTest", "--format=teamcity",
            expectedExitCode = 1, assertEmptyStdErr = false,
        )
        result.assertStderrContains("tests failed for module 'wasm-js-test-reports'")
        assertEquals(2, result.stdout.lineSequence().count { it.startsWith("##teamcity[testFailed ") })
        // The TeamCity renderer also wraps the skipped test in started/finished messages.
        assertEquals(5, result.stdout.lineSequence().count { it.startsWith("##teamcity[testFinished ") })
        result.assertStdoutContains("##teamcity[testIgnored ")

        val xml = readReport(result.buildDir, "wasm-js-test-reports")
        assertCounts(xml, tests = 5, failures = 2, skipped = 1)
        val failures = xml.getElementsByTagName("failure")
        val details = (0 until failures.length).joinToString("\n") { failures.item(it).textContent }
        assertContains(details, "SYNC <&> '\" |[]\nsecond line")
        assertContains(details, "ASYNC <&> '\" |[]\nsecond line")
        assertContains(xml.getElementsByTagName("system-out").item(0).textContent, "OUTPUT <&> '\" |[]")
        val cases = xml.getElementsByTagName("testcase")
        for (index in 0 until cases.length) {
            assertEquals("example.ReportingTest", (cases.item(index) as Element).getAttribute("classname"))
        }
    }

    @Test
    fun `filtered and skipped runs do not leave stale browser results`() = runSlowTest {
        val projectDir = testProject("wasm-js-test-reports")
        val selected = runCli(projectDir, "test", "--include-test=example.ReportingTest.passes")
        // kotlin-test emits @Ignore even when a different method is selected. Keep the same skipped result in XML.
        assertCounts(readReport(selected.buildDir, "wasm-js-test-reports"), tests = 2, skipped = 1)

        val empty = runCli(projectDir, "test", "--include-classes=example.DoesNotExist")
        assertCounts(readReport(empty.buildDir, "wasm-js-test-reports"), tests = 1, skipped = 1)

        val skipped = runCli(projectDir, "test", "--include-tag=not-a-wasm-tag")
        assertFalse(reportPath(skipped.buildDir, "wasm-js-test-reports").exists())
    }

    @Test
    fun `browser timeout writes the unfinished test as an error`() = runSlowTest {
        val result = runCli(
            testProject("wasm-js-test-reports"), "test", "--include-test=example.TimeoutTest.neverCompletes",
            expectedExitCode = 1, assertEmptyStdErr = false,
        )
        result.assertStderrContains("Timeout")
        val xml = readReport(result.buildDir, "wasm-js-test-reports")
        assertCounts(xml, tests = 2, errors = 1, skipped = 1)
        assertEquals("neverCompletes", xml.getElementsByTagName("error").item(0).parentNode.attributes.getNamedItem("name").nodeValue)
        assertContains(xml.getElementsByTagName("error").item(0).textContent, "Timeout")
    }

    @Test
    fun `browser page errors are visible even before a test starts`() = runSlowTest {
        val result = runCli(
            testProject("wasm-js-app-with-page-error"), "test",
            expectedExitCode = 1, assertEmptyStdErr = false,
        )
        result.assertStderrContains("Intentional page error from the test module")
        val xml = readReport(result.buildDir, "wasm-js-app-with-page-error")
        assertCounts(xml, tests = 1, errors = 1)
        assertContains(xml.getElementsByTagName("error").item(0).textContent, "Intentional page error")
    }

    @Test
    fun `browser run without any declared tests writes an empty report`() = runSlowTest {
        val projectDir = newEmptyProjectDir(setupWrappers = true)
        (projectDir / "module.yaml").writeText("product: wasm-js/app")
        (projectDir / "test" / "Empty.kt").createParentDirectories().writeText("class Empty")
        val result = runCli(projectDir, "test")

        assertCounts(readReport(result.buildDir, "new"), tests = 0)
    }

    private fun reportPath(buildDir: Path, module: String): Path =
        buildDir / "reports" / module / "wasmJs" / "debug" / "TEST-browser.xml"

    private fun readReport(buildDir: Path, module: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(reportPath(buildDir, module).toFile())

    private fun assertCounts(xml: Document, tests: Int, failures: Int = 0, errors: Int = 0, skipped: Int = 0) {
        assertEquals(tests.toString(), xml.documentElement.getAttribute("tests"))
        assertEquals(failures.toString(), xml.documentElement.getAttribute("failures"))
        assertEquals(errors.toString(), xml.documentElement.getAttribute("errors"))
        assertEquals(skipped.toString(), xml.documentElement.getAttribute("skipped"))
        assertEquals(tests, xml.getElementsByTagName("testcase").length)
    }
}
